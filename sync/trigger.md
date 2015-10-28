---
layout: page
sha: 38e3a0965da882b51e35f7c8a3a215dac9bd6b5b
---

# Start the Sync

This the final step of the "download" milestone. After this point, the application is able to discover nodes from the seed DNS,
maintain connection a certain number of them, detect when a new block comes out and catch up from the network.

However, it will not persist the blocks to disk, nor verify the validity of the data. These will be done later.

We have every piece of the Sync process in full or simplified form. The process just need to start.

Naturally, the Peer Manager will begin the Sync.

It has a downloader that it activates when it receives the `SyncFromPeer` message. The later comes from a handshake and from
an inventory message for a block we don't have yet.

The Peer Manager switches between two states: Either it's syncing or it is not syncing - or in other words, it will not pull
headers from more than one peer. 

The previous `receive` method is split into the sync/non sync receive and the common receive, handling the messages common
to both cases.

When we are in the middle of a Sync and receive another request `SyncFromPeer`, we put it aside for later. Once the Sync
completes, we dequeue from our stash.

```scala
def receive: Receive = ({
  case sfp @ SyncFromPeer(peer, hash) =>
    if (peersConnected.contains(peer) && getHeaderSync(hash).isEmpty) {
      context.become(syncingReceive(peer) orElse commonReceive, discardOld = false)
      synchronize(downloadManager.create(peer)).onComplete {
        case Success(true) =>
          self ! sfp
          self ! SyncCompleted
        case _ =>
          self ! SyncCompleted
      }
    }
}: Receive) orElse commonReceive

def syncingReceive(peer: ActorRef): Receive = {
  case _: SyncFromPeer => stash()
  case SyncCompleted =>
    unstashAll()
    context.unbecome()
}
```

The line 

```scala
if (peersConnected.contains(peer) && getHeaderSync(hash).isEmpty)
```

skips the whole process if the peer is now unavailable or if we already have the block. It is often the case that after
syncing from a peer, none of the remaining peers have anything new.

This concludes this milestone. Running the program at this stage outputs something similar to:

## Output

(Edited to cut down on the clutter)

```
[INFO ] 2015-10-28 16:46:51.964 [default-akka.actor.default-dispatcher-4] [akka://default/user/peermanager] PeerManager - Starting peer manager
[INFO ] 2015-10-28 16:46:51.965 [default-akka.actor.default-dispatcher-4] [akka://default/user/peermanager] PeerManager - Peers available = 25
[INFO ] 2015-10-28 16:46:51.968 [default-akka.actor.default-dispatcher-4] [akka://default/user/peermanager] PeerManager - Peers count = 0 (connecting = 0, not handshaked = 0)
[INFO ] 2015-10-28 16:46:52.233 [default-akka.actor.default-dispatcher-3] [akka://default/user/peermanager] PeerManager - Connected to /209.81.9.223:8333
[INFO ] 2015-10-28 16:46:52.266 [default-akka.actor.default-dispatcher-3] [akka://default/user/peermanager] PeerManager - Connected to /80.244.243.194:8333
[INFO ] 2015-10-28 16:46:52.280 [default-akka.actor.default-dispatcher-5] [akka://default/user/peermanager] PeerManager - Connected to /90.185.59.93:8333
[INFO ] 2015-10-28 16:46:52.379 [default-akka.actor.default-dispatcher-9] [akka://default/user/peermanager] PeerManager - Connected to /91.223.115.38:8333
[INFO ] 2015-10-28 16:46:52.677 [default-akka.actor.default-dispatcher-5] [akka://default/user/peermanager/$b] Peer - Handshake done
[INFO ] 2015-10-28 16:46:52.678 [default-akka.actor.default-dispatcher-5] [akka://default/user/peermanager] PeerManager - Handshake from Actor[akka://default/user/peermanager/$b#831774260]
[INFO ] 2015-10-28 16:46:52.679 [default-akka.actor.default-dispatcher-5] [] DownloadManager - Adding peer Actor[akka://default/user/peermanager/$b#831774260]
...
[INFO ] 2015-10-28 16:46:53.872 [default-akka.actor.default-dispatcher-11] [akka://default/user/peermanager/$b] Peer - Headers received (2000)
[INFO ] 2015-10-28 16:46:53.943 [default-akka.actor.default-dispatcher-11] [] DownloadManager - 96, Map(Actor[akka://default/user/peermanager/$b#831774260] -> 20, Actor[akka://default/user/peermanager/$c#-1204475395] -> 20, Actor[akka://default/user/peermanager/$d#-1359950447] -> 20, Actor[akka://default/user/peermanager/$e#-1998481925] -> 20)
[INFO ] 2015-10-28 16:46:53.966 [default-akka.actor.default-dispatcher-4] [akka://default/user/peermanager/$c] Peer - Peer received request to download List(000000006f016342d1275be946166cff975c8b27542de70a7113ac6d1ef3294f, ...
[INFO ] 2015-10-28 16:46:53.966 [default-akka.actor.default-dispatcher-4] [akka://default/user/peermanager/$d] Peer - Peer received request to download List(00000000ad2b48c7032b6d7d4f2e19e54d79b1c159f5599056492f2cd7bb528b, ...
...
[INFO ] 2015-10-28 16:46:54.172 [default-akka.actor.default-dispatcher-22] [akka://default/user/peermanager/$b] Peer - Block received 00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048
[INFO ] 2015-10-28 16:46:54.176 [default-akka.actor.default-dispatcher-26] [] DownloadManager - 96, Map(Actor[akka://default/user/peermanager/$b#831774260] -> 19, Actor[akka://default/user/peermanager/$c#-1204475395] -> 20, Actor[akka://default/user/peermanager/$d#-1359950447] -> 20, Actor[akka://default/user/peermanager/$e#-1998481925] -> 20)
[INFO ] 2015-10-28 16:46:54.182 [default-akka.actor.default-dispatcher-18] [akka://default/user/peermanager/$b] Peer - Block received 000000006a625f06636b8bb6ac7b960a8d03705d1ace08b1a19da3fdcc99ddbd
[INFO ] 2015-10-28 16:46:54.182 [default-akka.actor.default-dispatcher-12] [] DownloadManager - 96, Map(Actor[akka://default/user/peermanager/$b#831774260] -> 18, Actor[akka://default/user/peermanager/$c#-1204475395] -> 20, Actor[akka://default/user/peermanager/$d#-1359950447] -> 20, Actor[akka://default/user/peermanager/$e#-1998481925] -> 20)
...
```

After the node got 5 peers, it asked for headers and got 2000 headers back. It then started downloading blocks.

```
DownloadManager - 96, Map(Actor[akka://default/user/peermanager/$b#831774260] -> 18, Actor[akka://default/user/peermanager/$c#-1204475395] -> 20, Actor[akka://default/user/peermanager/$d#-1359950447] -> 20, Actor[akka://default/user/peermanager/$e#-1998481925] -> 20)
```

- 96: The task list has 96 entries left. Since each batch is 20 blocks (from the setting `batchSize` in the config file), that's 1920 blocks,
- We have 4 peers that got the remaining 80 blocks. Each got 20. Peer $b has 18 more to go because it has downloaded 2 so far. The other peers
haven't downloaded anything yet.

After a while...

```
[INFO ] 2015-10-28 17:10:29.565 [default-akka.actor.default-dispatcher-2] [akka://default/user/peermanager/$b] Peer - Block received 00000000dfd5d65c9d8561b4b8f60a63018fe3933ecb131fb37f905f87da951a
[INFO ] 2015-10-28 17:10:29.565 [default-akka.actor.default-dispatcher-12] [] DownloadManager - 0, Map(Actor[akka://default/user/peermanager/$h#1228801928] -> 0, Actor[akka://default/user/peermanager/$b#-570868046] -> 0, Actor[akka://default/user/peermanager/$d#1652872551] -> 0, Actor[akka://default/user/peermanager/$c#917345383] -> 0)
[INFO ] 2015-10-28 17:10:29.937 [default-akka.actor.default-dispatcher-2] [] PeerManager - New tip = 00000000dfd5d65c9d8561b4b8f60a63018fe3933ecb131fb37f905f87da951a
[INFO ] 2015-10-28 17:10:29.958 [default-akka.actor.default-dispatcher-2] [] PeerManager - Height = 2000, POW = 8594360698833
[INFO ] 2015-10-28 17:10:29.960 [default-akka.actor.default-dispatcher-10] [akka://default/user/peermanager/$c] Peer - Peer received request for headers 00000000dfd5d65c9d8561b4b8f60a63018fe3933ecb131fb37f905f87da951a
[INFO ] 2015-10-28 17:10:31.102 [default-akka.actor.default-dispatcher-10] [akka://default/user/peermanager/$c] Peer - Headers received (2000)
```

It finished with the first 2000 blocks and updated the blockchain. The new tip is `00000000dfd5d65c9d8561b4b8f60a63018fe3933ecb131fb37f905f87da951a` which is block 2000 and the cumulative proof of work was 8594360698833.
Then it continued syncing because there are more blocks. This time it is pulling headers from peer `$c`

It will keep going on until it is fully synced. However, there isn't much point in doing so since all the block data is dropped. Headers
are in the database though.

