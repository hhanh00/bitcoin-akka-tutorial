---
layout: page
sha: 8ba9bac8403386b2b0c2a2197f03a2e9b0bf156f
---

# Message Handler

The message parser is encapsulated in its own actor in order to isolate it from crashes. Since input comes from the remote side
we have no guarantee that they are well-formed messages. The parser verifies the message structure but delegates parsing of
the payload to functions that don't check for overflow. They follow the "happy path" which is not safe when the input hasn't
been [sanitized][1]. Bitcoin-akka approach is to isolate the main actors from protocol faults by creating an actor that
is expected to fail under these conditions: The "let-it-crash" philosophy of Erlang. That actor is automatically restarted
by the framework and resumes processing further messages.

```scala
class MessageHandlerActor(connection: ActorRef) extends Actor with MessageHandler with ActorLogging {
  connection ! Tcp.Register(self)
  def receive = {
    case Tcp.Received(data) => frame(data).flatMap(parse).foreach(context.parent ! _)
    case bm: BitcoinMessage => connection ! Tcp.Write(bm.toMessage())
    case other => context.parent ! other
  }

  private def parse(mh: MessageHeader): Option[BitcoinMessage] = {
    mh.command match {
      case "version" => Some(Version.parse(mh.payload))
      case "verack" => Some(Verack)
      case _ => None
    }
  }
}
```

We finish up the [handshake]({{site.baseurl}}/handshake/handshake.html) in the next section.

[1]: https://xkcd.com/327/

