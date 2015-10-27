---
layout: page
sha: 1c18fb92e141b7ad6a568f7e0a8e859d3fcc1c42
---

# Bitcoin Message

Previously we wrote a function that parses the header of a message leaving the payload as a raw array of bytes. Now we are going
to do something with the payload. Based on the command field of the header, we can determine the type of the message and
invoke the appropriate message parser.

First of all, let's define a base class from which all messages derive.

```scala
trait BitcoinMessage extends ByteOrderImplicit {
  import BitcoinMessage._
  val command: String
  def toByteString(): ByteString
  def toMessage() = {
...
  }

  def calcChecksum(payload: ByteString) = ???
}
```

We are saying that every message should have a command name and a serializer to bytes. We can add a header to the payload
once we have both of them.

The `calcChecksum` is left undefined at the moment, we'll add it [next]({{site.baseurl}}/handshake/checksum.html).
