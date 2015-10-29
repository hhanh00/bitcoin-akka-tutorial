---
layout: page
sha: 34e739adf0962d43586294f13cb21c4e3aa52e38
---

# Block Validation

Block validation works on the same principle as header validation. It uses the same `scanM` function
with the State monad but this time with a different check function and check state.

There are more rules and one complication: A transaction can use outputs from a previous transaction from the same block.

This complicates things quite a bit because we need to update the UTXO db as we are validating a block. And if the block
turns out to be invalid, we need to rollback all these changes since we are rejecting that block. Also, when we start
check a chain of blocks, they may not be starting at our current tip. For instance, if we receive blocks that form a fork
their transactions start from an older block. Fortunately, we have already all the tools to help us deal with these cases.

- we can rollback our UTXO db to any previous point of the blockchain by using the undo block data,
- we can create temporary changes to the UTXO db and remove them altogether with the `InMemUTXODb`.

With that in mind, checking blocks is first checking the overall structure of a block and then checking its contents:

```scala
val r = for {
  _ <- checkBlock(block)
  _ <- checkBlockContents(block, header.height)
} yield ()
val (updatedCheckData, result) = r.run(checkData)
Trampoline.done((updatedCheckData, result.isDefined))
}
```

For `checkBlock`, we should make sure that:

- the block size doesn't exceed the limit,
- there is at least one transaction,
- there are no duplicate transactions,
- the merkle root of the transaction tree matches the value in the header

```scala
private def checkBlock(block: Block): BlockCheckStateOption[Unit] = {
  val r = for {
    _ <- checkSize(block)
    _ <- (block.txs.length > 0).option(())
    txHashesArray = block.txs.map(tx => hashToString(tx.hash)).toList
    txHashesSet = txHashesArray.toSet
    _ <- (txHashesSet.size == block.txs.size).option(())
    _ <- checkMerkleRoot(block)
  } yield ()

  val x = r.point[BlockCheckState]
  OptionT.optionT(x)
}
```

When we check the block contents, we verify that:

- the fees + the block reward does not exceed the coinbase output,
- the number of signature checks isn't greater than the limit,
- the coinbase format is ok (it should be between 2 and 200 bytes, and have the height in it),

```scala
private def checkBlockContents(block: Block, height: Int) = {
  val utxoDb = new InMemUTXODb(db)
...
  val r = for {
    _ <- (feesAndSighashCheckCount.forall(_.isDefined)).option(())
    (totalFees, totalSighashCheckCount) = feesAndSighashCheckCount.map(_.get).reduceLeft { (a, b) => (a._1 + b._1, a._2 + b._2) }
    _ = log.debug(s"Sighash check count = ${totalSighashCheckCount}")
    _ <- (totalSighashCheckCount <= Consensus.maxSighashCheckCount).option(())
    _ <- Consensus.checkCoinbase(block.txs(0), height, totalFees)
  } yield ()

  if (r.isDefined) {
    val op = updateUTXODb(block, height)
    (checkData copy (txLog = op :: checkData.txLog), r)
  }
  else (checkData, r)
}
```

This step involves temporary updating the UTXODb afterwards.

Last but not least, we check the transactions:

- they should not double-spend: their input must be in the UTXO Db
- they should not spend more than they use: the total output value should be lower than the total input value
- they should be "final" (relative to lockTime and sequence)
- and finally the input & output scripts must check. For this part, we use `libbitcoinconsensus`.

This commit also include a helper class to count the number of signature checks. It's unfortunate that the value doesn't come
back from the consensus library because this is the only place where we have to deal with the [Script][1] language. I hoped
we wouldn't have to but at least we don't have to write a full blown parser.

[1]: https://en.bitcoin.it/wiki/Script

The next commit is the [JNI wrapper]({{site.baseurl}}/validation/jni.html) for `libbitcoinconsensus`.


