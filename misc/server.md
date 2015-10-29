---
layout: page
sha: 47086e21b9005a0ed7ebb3919bd3cc9c42b17003
---

# Accepting Incoming Connections

Until now, we have been initiating all our connections. 

To do so, we simply tell the IO subsystem to bind to a local port.

```scala
IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 8333))
```

That takes care of accepting connections. However there is something that we arguably should have done before. Writing
to the TCP stack has to be done one `Tcp.Write` at a time. It was not a problem when we were not relaying blocks
because we were only sending `GetHeaders` and `GetData`, but with the transaction relay we are sending more stuff
in parallel.

Akka will reject a Tcp.Write if there is another one outstanding. There are several ways to handle the backpressure,
we are just going to do the simplest form. We ask for an `Ack` message and buffer writes until we get it. When
we receive the Ack, we emit any outstanding message. As a matter of fact, collisions aren't very common because
we already buffer Inv in the peer actor.


```scala
var acknowledged = true
var writeBuffer = Queue.empty[ByteString]
private def sendMessage(bm: BitcoinMessage) = {
  bm match {
    case block: Block => log.info(s"block ${hashToString(block.header.hash)}")
    case _ =>
  }
  val bytes = bm.toMessage()
  writeBuffer :+= bytes
  if (acknowledged) {
    connection ! Tcp.Write(bytes, Ack)
    acknowledged = false
  }
}
private def acknowledge() = {
  writeBuffer = writeBuffer.drop(1)
  if (writeBuffer.isEmpty)
    acknowledged = true
  else
    connection ! Tcp.Write(writeBuffer(0), Ack)
}
```

Next: [Running the Regression Tests]({{site.baseurl}}/misc/test.html)
