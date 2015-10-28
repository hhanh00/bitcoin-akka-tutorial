---
layout: page
sha: 57789d02166c9f769db948a65ced5f390e6e21bc
---

# Sync Interface

Ironically, Sync works asynchrounously. Like most processes that involve network communication, it can spend a long time waiting
for events that are out of its control. For example, it has to wait for the response to "GetHeader" before it can proceed. Furthermore,
downloading a large number of blocks also leads to large time spent in I/O and therefore CPU waiting.

Traditionally programming async involves using callbacks. We call a function that sends a request out and returns right away. When
the response comes back, the callback is invoked. Another common way is to use an event loop. When a response is available,
an event comes and unblocks the loop. Both models are viable and are used in many applications. However, they break the sequential
flow of the program and it is difficult to see what the program expects to happen next. With [Futures][1] we can keep writing code
that looks sequential but that work asynchronously. Along with the actor model, they provide a powerful style for writing concurrent
code. 

The entire sync process is encapsulated in a single method.

```scala
trait Sync { self: SyncPersist =>
  def synchronize(provider: SyncDataProvider): Future[Boolean]
}
```

`synchronize` returns a boolean value at some point in the future. That value indicates if we should repeat the process because
there may be more data or if it completed.

```scala
self: SyncPersist =>
```

This indicates that the class that implements `Sync` must also implement `SyncPersist`. It is the interface by which the blockchain
is saved. In other words, we are saying that Sync depends on having an implementation of SyncPersist but Sync is not a persister.

```scala
trait SyncPersist {
  def saveBlockchain(chain: List[HeaderSyncData])
  def loadBlockchain(): Blockchain
  def setMainTip(newTip: Hash)
}
```

Also, `synchronize` takes a `SyncDataProvider` which is an interface to the source of data.

```scala
trait SyncDataProvider {
  def getHeaders(locators: List[Hash]): Future[List[BlockHeader]]
  def downloadBlocks(hashes: List[HeaderSyncData]): Future[Unit]
}
```

Everytime synchronize is called, it gets a different instance of a provider because the provider is bound to a particular set of
peers.

Next: [Communication during Sync]({{site.baseurl}}/sync/peer.html)
