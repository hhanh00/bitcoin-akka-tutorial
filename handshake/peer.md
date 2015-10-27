---
layout: page
sha: 0095746a6956559c9ce6d019bce8536a90f06c72
---

# Initial Peer logic

We are getting close to our handshake. In this section, we'll work on the Peer actor and implement a simple
state machine.

The peer actor has to react to events coming from our application side and from the remote node. Because of this,
it doesn't follow a linear behavior but reacts in response to outside inputs. The best way to code this type
of behavior is with a state machine.

```scala
class Peer(connection: ActorRef) extends FSM[Peer.State, Peer.Data] with ActorLogging {
  import Peer._

  startWith(Initial, NoData)

  when(Initial) {
    case Event(Tcp.Received(data), _) =>
      log.info(s"Received ${Hex.encodeHexString(data.toArray)}")
      stay

    case Event(_: ConnectionClosed, _) =>
      log.info("Peer disconnected")
      context stop self
      stay
  }

  initialize()
}
object Peer {
  trait State
  object Initial extends State

  trait Data
  object NoData extends Data
}
```

For the moment, the peer has just one state. It enters the Initial state with has no associated data and stays
there. This commit just shows the structure of an actor state machine in Akka.

Next: Parse the messages involved in the [handshake]({{site.baseurl}}/handshake/parse-handshake.html)

