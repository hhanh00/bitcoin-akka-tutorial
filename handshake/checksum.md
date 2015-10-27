---
layout: page
sha: c985eec47d6338d1172a9765355bd69818da1342
---

# Checksum

The message checksum adds a certain level of protection against message corruption. It's calculated based on the payload
and will change if the payload is modified.

Bitcoin uses the SHA-256 hash function. It is applied twice to the payload and the checksum is the first 4 bytes of the
result.

```scala
def calcChecksum(data: ByteString) = {
  val hash = dsha(data.toArray[Byte])
  java.util.Arrays.copyOfRange(hash, 0, 4)
}

val sha256: (Array[Byte]) => Array[Byte] = { data =>
  val md = MessageDigest.getInstance("SHA-256")
  md.update(data)
  md.digest()
}
val dsha = sha256 compose sha256
```

`sha256` is defined as a value that has a type of "function". In doing so, we can use the functions that apply of functions
themselves such as compose. This feature is one of the benefits of FP. We can work with scalar values like integers, doubles, etc.
but also with functions.

Next: Read/Write [primitive types]({{site.baseurl}}/handshake/primitive.html).
