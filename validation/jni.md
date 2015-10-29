---
layout: page
sha: 4c1bb3f38bb8c947d051a919d6819fc0b21b213e
---

# JNI Wrapper

The wrapper bridges the native function from `libbitcoinconsensus` to Scala.

```scala
  @native def verifyScript(scriptPubKey: Script, tx: Array[Byte], index: Int, flags: Int): Int
```

This is the end of this milestone. At this point, you have a node that can catch up and verify blocks from the Bitcoin network.
It will stay on the fork that has the highest proof of work and maintain a database of UTXO.

The next milestone will be the final one. We will finish the small remaining tasks:

- relay transactions
- accept incoming connections
- auto generate parsers and serializers for the Bitcoin messages
