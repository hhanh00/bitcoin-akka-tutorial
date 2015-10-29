---
layout: page
sha: 4ba058f3f9dde24be473cd3538547b23e650558d
---

# Update UTXO Db

The most significant change introduced by this commit is the `updateUTXODb` function.

```scala
private def updateUTXODb(block: Block, height: Int): UTXOBlockOperation = {
  UTXOBlockOperation.buildAndSaveUndoBlock(db, block, height)
  val op = UTXOBlockOperation(block.header.hash, height, false)
  op.run(db)
  op
}
```

The rest of the changes are there to hook up this function into the Sync workflow at a place that is close to its final location.
Unfortunately, that means also adding code that make no difference at all - they are all pass through and are the purpose of the 
next milestone. Let's take these for granted for the time being.

At this point we have a working UTXO Db that correctly tracks the unspent transactionoutputs.

Before we conclude the "persist" milestone, let's add an import/export utility that helps quickly bring the UTXO Db up to date
from a file of blocks.

Next: [Import/Export]({{site.baseurl}}/utxo/import.html)
