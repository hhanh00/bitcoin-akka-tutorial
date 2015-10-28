---
layout: page
sha: d39d9a2e254f0adf6a6dcedc7e9d33a04f26fa11
---

# Peer communication during Sync

In this commit, the peer state machine handles the messages used during Sync. Currently once the peer reaches the Ready
state, it does nothing else since the FSM has the `NullFunction` for its handler.

We need to add handlers for the four messages: `GetHeaders`, `GetBlocks`, `Headers` and `Block`. GetBlocks is the only message
that is strictly not a Bitcoin protocol message. But in fact, it is very close to `GetData`, the message that will be sent out.

```scala
case class GetBlocks(hsd: List[HeaderSyncData])
```

We have a different message because we want to keep a list of `HeaderSyncData` rather than a list of `InvEntry`. InvEntry
is just a hash whereas HeaderSyncData has the block header, the height and the proof of work. It is something that we
need to build after we have the new headers and we want to carry this information throughout the rest of the Sync process.

The handlers for GetHeaders/Headers and GetBlocks/Blocks use the same pattern. It is assumed that a peer cannot be asked for
headers and blocks at the same time, i.e. the caller should get the response to GetHeaders before it asks for blocks.
Actually, only one request can be done at a time and it's okay for the peer to store a reference to the actor that sent it.

These states have timers that fire if the peer doesn't make progress because the remote side doesn't reply. When the timer
expires, the peer gets a `StateTimeout` message which is caught in the common handler `whenUnhandled`. The peer shuts itself
down on a time out.

The peer doesn't track which blocks it has received and therefore cannot tell if the GetBlock request finished. It is actually
the requestor that informs the peer of this fact with the `GetBlocksFinished` message. The "getblocks" timer is reset on every
block so that it triggers only if no block has been received before the time runs out.

In the next section, we'll make the peer manager maintain a [pool of peers]({{site.baseurl}}/sync/pool.html)
of a configurable size.
