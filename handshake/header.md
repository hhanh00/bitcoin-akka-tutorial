---
layout: page
sha: 6073be07bb96cd3563117e82e295e057e472ddee
---

# Message header

Every message in the bitcoin protocol is in binary form transported over TCP/IP. A node sends and receives
bytes organized as an outgoing and incoming stream. It's up to the application to decide where a message
starts and ends.

For Bitcoin, there are only messages put on the stream. Therefore a message starts when the preceding one
ends. However, we still need to know where a message ends. This is one of the purpose of the message header.

The header has a fixed size and contains the length of the message. The `MessageHandler` decodes a ByteString
into a `MessageHeader`. Because the input may not contain enough data, we have to return an `Option` value.

Option types denote either one of zero element of the containing type. They help avoiding the common bug of
dereferencing `null` because the compiler can distinguish between a mandatory value and an optional one: `T`
vs `Option[T]`.

In fact, the signature is

```scala
def parseMessageHeader(bs: ByteString): Option[(MessageHeader, ByteString)]
```

The parser returns a pair of values or nothing. The pair has the message header and the remaining bytes as a new
ByteString. Following the principles of Functional Programming, we try to avoid mutating values. We won't change
the incoming ByteString, instead we return a new one (the library is optimized and will not make a physical copy).

Next: Parse multiple messages into [frames]({{site.baseurl}}/handshake/frame.html)
