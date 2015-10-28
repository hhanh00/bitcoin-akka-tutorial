---
layout: page
sha: a29ff720d2ad0ad328626266c58db9bddc7c4c59
---

# Downloader

The downloader is the final part of the Sync component. We have code that drives the process (`Sync`) and a persister module
`SyncPersistDb` to save the data but we don't have a way to get the data yet. The peer actors can communicate with the remote
nodes but we don't have anything to give them commands.

The downloader fulfills this need. It is essentially a helper class that provides a factory for a `SyncDataProvider`.

```scala
class DownloadManager(context: ActorRefFactory)(implicit settings: AppSettingsImpl) {
  def create(syncPeer: ActorRef) = new SyncDataProvider {
    override def getHeaders(locators: List[Hash]): Future[List[BlockHeader]] = internalGetHeaders(syncPeer)(locators)
    override def downloadBlocks(hashes: List[HeaderSyncData]): Future[Unit] = internalDownloadBlocks(hashes)
  }
}
```

getHeaders and downloadBlocks are forwarded to the downloader. The difference between the two methods is that we choose
to get headers from a single peer, the one that told us that it has blocks we don't - whereas we always download blocks
from our whole set of peers.

To get headers, the downloader sends a `GetHeaders` message and then wait for a `Headers` reply. It creates a temporary
actor that encapsulates this simple sequence of messages. However, doing just that would not be correct. If the peer actor
shuts down (because of a timeout or a remote peer disconnection), the downloader would be blocked indefinitively, waiting
for a reply that never comes. We could set a timeout but we would have to wait until it expires needlessly.

Fortunately, the actor framework offers a way to get a notification message when another actor terminates. When we do
`context watch syncPeer` we register for "death watch" on `syncPeer`. If it terminates, we get a `Terminated` message.

With this mechanism, we can correctly implement all scenarios:

```scala
def internalGetHeaders(syncPeer: ActorRef)(locators: List[Hash]): Future[List[BlockHeader]] = {
  val p = Promise[Headers]()
  context.actorOf(Props(new Actor {
    context watch syncPeer
    syncPeer ! GetHeaders(locators, zeroHash.array)

    def receive: Receive = {
      case headers: Headers =>
        context unwatch syncPeer
        context stop self
        p.success(headers)
      case Terminated(_) =>
        p.failure(new TimeoutException())
    }
  }))

  p.future.map(headers => headers.blockHeaders)
}
```

The promise holds a future that we can programatically complete with success or failure. 

Downloading blocks is similar but is more complex because it involves a pool of peers and not just one.

First of all, the pool can change dynamically during our download. It is quite common to have a peer time out
because it doesn't give us a block in time. In many cases, it happens in a middle of a query; we received a few
blocks and then it times out. We don't want to redownload anything we have so we need to keep the progress made
per block and not per `GetBlock`.

The state kept during block download is:

```scala
case class DownloaderState(p: Promise[Unit], tasks: List[Task], hashToHSD: Map[WHash, HeaderSyncData], workerToHashes: Map[ActorRef, Set[WHash]])
```

- `p` is the promise that we complete at the end of the download
- `tasks` is a list of unassigned tasks. A task is a list of blocks to download. We receive a list of blocks and slice it into
batches of fixed size (configured in application.conf). In some cases, there are more batches than peers and we park the pending
batches in `tasks`,
- `hashToHSD` is a map from header hashes to the HeaderSyncData. We get in input a list of `HeaderSyncData`, the protocol deals
with hashes, hashToHSD maps between the two so that we can relate the responses to the requests,
- `workerToHashes` maps each peer to a list of hashes that it is assigned to download. As blocks come back, their hashes are removed
from the set.

As usual, DownloaderState is a immutable class. We have functions that transition the downloader from one state to another. Each of them
do a particular action. These functions have the same signature: They take a DownloaderState and returns a new DownloaderState.

```scala
type DownloaderStateFunc = DownloaderState => DownloaderState
```

For example, when we want to assign a task:

```scala
def assignTask(self: ActorRef): DownloaderStateFunc = { s =>
  (for {
    task <- s.tasks.headOption
    idleWorker <- s.workerToHashes.find(_._2.isEmpty).map(_._1)
  } yield {
      idleWorker.tell(Peer.GetBlocks(task), self)
      s copy (tasks = s.tasks.tail, workerToHashes = s.workerToHashes.updated(idleWorker, task.map(hsd => hsd.blockHeader.hash.wrapped).toSet))
    }) getOrElse s
}
```

The function takes the current state and looks at the task list and workers that have nothing assigned to them. If we have both items,
it will send a `GetBlock` message to the idle peer on behavor of the actor `self`, then it returns a new state which is a copy with
an updated task list and worker assignment.

This programming style may look uncommon in imperative programming but it has the advantage of eliminating side effects. Everything
that `assignTask` touches is in the downloader state (well, and the actor message). Once these functions are written and tested,
they combine with ease. If on the contrary we had functions that looks at external variable and modifies them, we would have to check
that these don't interfere with each other once we combine functions.

Receiving a block is another example of such function:

```scala
def receiveBlock(peer: ActorRef, block: Block): DownloaderStateFunc = { s =>
  val wHash = block.header.hash.wrapped
  (for {
    hashes <- s.workerToHashes.get(peer)
  } yield {
      val height = s.hashToHSD(wHash).height
      // TODO: Save block -- blockStore.saveBlock(block, height)
      s copy (workerToHashes = s.workerToHashes.updated(peer, hashes - wHash))
    }) getOrElse s
}
```

(Hashes are wrapped in a WrappedArray.ofByte when they are used as keys in maps because simple arrays of bytes have 
reference equality semantics and can't be used as is in this context)

This function is extra careful - it checks that the peer is indeed a worker even though it should be. It is to cover the case
when a peer terminates (and we get the termination message) but then continues to send us a block. In practice this should not
happen because of the way actors behave. After they stop, they can't send messages.

There are a few more functions:

- `completeWorker` sends a `GetBlocksFinished` down to a peer if it has finished its current task and then pull another assignment
- `failWorker` puts the current task of a worker back to the task list because that worker has failed, and removes that peer from
the worker set
- `add` adds a new peer to the worker set

They are in the commit.

As we saw, the block downloader maintains its own list of workers. It's the same as the pool of peers but in order to keep
actors encapsulated, the downloader doesn't access the Peer Manager private data. It would be very bad since the Peer Manager
is running in its thread pool. To keep things in sync, the peer manager informs the downloader of new peers by calling `addPeer`.
The downloader has its own monitoring on the peers through death watch so it knows when they are terminated. The last thing
is to run an internal actor that responds to messages from peers just like in the case of GetHeaders.

However, this internal actor remains alive but idle once the download completes because it should keep tracking the worker pool.

Here's the full block download handler:

```scala
def internalReceive(state: DownloaderState): Receive = {
  log.info(s"${state}")
  checkCompleted(self)(state)

  {
    case AddPeer(peer) =>
      log.info(s"Adding peer ${peer}")
      context watch peer
      context become internalReceive((add(peer) andThen assignTask(self))(state))

    case block: Block =>
      val peer = sender
      context become internalReceive((receiveBlock(peer, block) andThen completeWorker(peer, self))(state))

    case Terminated(peer) =>
      log.info(s"Peer ${peer} terminated")
      context become internalReceive((failWorker(peer) andThen assignTask(self))(state))

    case DownloadFinished =>
      state.p.success(())
      context become idleReceive(IdleState(state.workerToHashes.keys.toSet))
  }
}
```

Whenever we call `internalReceive`, we check if work is complete and return a handler. Note that `checkCompleted` is called
only when we call internalReceive and not when we receive a message. `internalReceive` returns a handler - it is not
a handler. The handler is the partial function defined by the `{ case ... }`

This code combines the small functions `add` and `assignTask` with `andThen` to say that when the downloader gets the `AddPeer`
message it adds it to the pool and then assigns a task. The `state` is never directly modified and we can't have bugs due to 
incorrect usage of the state data.

The downloader has another state `IdleState(peers: Set[ActorRef])` in use when it is idle and just needs to track the worker pool.

This code could be the most unusual code seen so far but is fairly representative of the functional programming style. It
may get some time to get used to but is less bug-prone.

In the next section, we finish up with this milestone by [triggering the Sync]({{site.baseurl}}/sync/trigger.html)
on a handshake and on a new block.