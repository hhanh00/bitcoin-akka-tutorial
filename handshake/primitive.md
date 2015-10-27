---
layout: page
sha: 82d4e0dd85e7cfb013811b8dddbb7e1e9fa883ee
---

# Read/Write primitive types

Most of primitive types used by the Bitcoin protocol are your regular C primitive types: uint16, uint32, uint64.
To these, it adds a shorter form of an integer called VarInt. It's basically a variable length integer skewed 
towards lower values. Short values will take less room than longer ones. The savings are hardly consequential
but it is now part of the standard so we have to deal with it.

Reading and writing into byte strings has been done with the help of `ByteIterator` and `ByteStringBuilder`, 
two classes from Akka. We would like to add more types to these so that the syntax will look uniform. 
Fortunately, Scala offers a way to do that with implicit classes.

We have `ByteStringBuilderExt` and `ByteStringIteratorExt` that implicitly converts from their namesakes
and add the methods for the new types.

```scala
implicit class ByteStringBuilderExt(bb: ByteStringBuilder) {
  def putVarString(s: String) = {
    putVarInt(s.length)
    bb.putBytes(s.getBytes)
  }
}
```

When one of these classes is in scope, the compiler will automatically insert a conversion if needed so that we can
write `bb.putVarString(s)` directly instead of `ByteStringBuilderExt(bb).putVarString(s)`.

Next we'll spin up some simple network code and [connect to a peer]({{site.baseurl}}/handshake/connect.html)
