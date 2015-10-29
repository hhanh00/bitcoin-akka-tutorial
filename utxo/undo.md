---
layout: page
sha: 6e70505f2c85326c4e058b238155b6d3405f9cb1
---

# Undo Blocks

Most of the time undo blocks aren't going to be used. Most of the time there is no competing block and the blockchain
proceeds forward. We apply transactions by deleting the outputs that are spent and add new entries for the freshly
created ones.

Occassionally though, a block that was processed earlier ends up on a shorter fork of the blockchain. In this case,
we have to switch to the better chain by first reversing earlier modifications. It is easy to undo an insertion, 
we just have to delete the entry. The insert has the key, we can use that key and issue a delete. Undoing a delete
is more complicated. We have the key but we no longer have the previous value in the database.

That's why we have the undo records. They are the entries that were deleted. To undo a block: 

- we scan the previous block and drop the records that are in the transaction outputs. That takes care of inserts,
- we scan the undo file and insert these records back. That takes care of deletes.

From a transaction, we get the list of changes it makes to the UTXODb by looking at its inputs and outputs. This function
is pure, i.e it doesn't affect the database.

```scala
def ofTx(tx: Tx, isCoinbase: Boolean, height: Int): UTXOEntryList = {
  val deleted = if (!isCoinbase) tx.txIns.map(txIn => UTXOEntry(txIn.prevOutPoint, None)) else Array.empty[UTXOEntry]
  val added = tx.txOuts.zipWithIndex.map { case (txOut, i) =>
    val outpoint = OutPoint(tx.hash, i)
    val utxo = UTxOut(txOut, if (isCoinbase) Some(height) else None)
    UTXOEntry(outpoint, Some(utxo))
  }
  (deleted ++ added) toList
}
```

From a list of changes to the database we can compute the reverse actions. Note that we also need the current database 
to look up the previous output in case of deletes. This function is also pure.

```scala
def undoOf(db: UTXODb, entries: UTXOEntryList): UTXOEntryList = {
  entries.flatMap { entry =>
    entry.value match {
      case Some(e) => Some(UTXOEntry(entry.key, None))
      case None =>
        db.get(entry.key) map { prevTxOut =>  // this should be checked earlier
          UTXOEntry(entry.key, Some(prevTxOut)) }
    }
  }
}
```

And we can execute a bunch of changes into a UTXO transaction, i.e. group all the changes made by one block into an operation.

```scala
object UTXOForwardOnlyBlockOperation {
  def run(db: UTXODb, block: Block, height: Int): Unit = {
    for {
      (tx, i) <- block.txs.zipWithIndex
      entry <- UTXO.ofTx(tx, i == 0, height)
    } {
      db.add(entry)
    }
  }
}
```

The underlying database doesn't have the concept of a transaction, so we are essentially simulating it by creating
objects that track the changes made by blocks.

When it executes `run`, it will apply the changes. On a `undo`, it will revert these changes. A undo block (`isUndo = true`) operation
does the opposite.

```scala
case class UTXOBlockOperation(hash: Hash, height: Int, isUndo: Boolean)(implicit blockStore: BlockStore) extends UTXOOperation {
  val log = LoggerFactory.getLogger(getClass)
  override def run(db: UTXODb): Unit = if (isUndo) undo_(db) else do_(db)
  override def undo(db: UTXODb): Unit = if (isUndo) do_(db) else undo_(db)

  private def do_(db: UTXODb) = {
    log.info(s"Applying tx in block ${hashToString(hash)}")
    val block = blockStore.loadBlock(hash, height)
    UTXOForwardOnlyBlockOperation.run(db, block, height)
  }
  private def undo_(db: UTXODb) = {
    log.info(s"Undoing tx in block ${hashToString(hash)}")
    blockStore.loadUndoBlock(hash, height).foreach(db.add(_))
  }
}
```

At this point, we have all the building blocks to work with the UTXO Db. We can create a UTXO block transaction. Apply it and then
later reverse it. So now it is a matter of having the Sync code use it.

Next: [Apply transactions to UTXO Db]({{site.baseurl}}/utxo/apply.html)
