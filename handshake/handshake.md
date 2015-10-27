---
layout: page
sha: 798c3daa15c44b9b5d470a11f639ca211556df2d
---

# Handshake

To implement the handshake we need to add a new state to the Peer state machine that is reached after the handshake 
completes.

The peer moves from "Initial" to "Ready" once it receives both Version and Verack messages from the remote peer.

The progress of the handshake is captured in a data object associated with the Initial state.

```scala
case class HandshakeData(height: Option[Int], ackReceived: Boolean) extends Data
```

It starts with `(None, false)` and changes when we receive Version and Verack. Since we don't know the order
we are getting them, the check for completion is in both branches. It matches the data against having a height
and having received the ack.

```scala
private def checkHandshakeFinished(d: HandshakeData) = d match {
  case HandshakeData(Some(height), true) =>
    goto(Ready)
  case _ =>
    stay
}
```

On a transition to the Ready state, we send the Handshaked message to the parent of the peer that is to say
the peer manager.

```scala
context.parent ! Handshaked(nextStateData.asInstanceOf[HandshakeData].height.get)
```

This completes the first part of the tutorial. We have now a node that can connect to a peer and successfully complete
the handshake.
