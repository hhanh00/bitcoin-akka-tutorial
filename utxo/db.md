---
layout: page
sha: a1ea3d5563c9329b3925fd54e8ba4d6789b54975
---

# UTXO Db

UTXO Db is a key/value store. Essentially, it is a single table with two binary columns. We can look up by
key and it returns the corresponding value if there is one.

The key type is the binary representation of an `OutPoint` because transaction inputs refer to previous
outputs using the Outpoint structure.

An Outpoint is a transaction hash and the output offset. I find that a transaction is much alike a piece of
jigsaw puzzle. It is designed to fit at a very precise place and even though from the outside they look
quite similar, what is inside makes them unique. Transactions "consume" the output of a previous transaction
and in turn "produce" their own outputs. An output can only be used once, after which it is "spent". Therefore,
in order to validate transactions, we are only interested in "unspent" outputs. Moreover, a transaction
can and ususally do create more than one output and it is necessary to indicate which of these outputs is 
consumed.

The key type is

```scala
case class OutPoint(hash: Hash, index: Int)
```

Now that the key type is known, what is the value type? We surely need to have the content of the transaction
output: the output amount (in satoshis) and the output script. These are needed in order to validate the
transaction:

- the output script and the input script are combined and together they must form a valid script. This is like
tearing a paper in two and giving one piece to each party. We identify a good transaction by its ability to
present the part that matches the one that is in the previous output.
- the output amount is needed so that a transaction cannot spend more than it.

But we need to keep one more field. The coinbase transaction (the transaction that has the block reward) cannot
be spent before 100 blocks has passed. For these transactions and only these we must keep the height of the
block that has them.

The value type is

```scala
case class UTxOut(txOut: TxOut, height: Option[Int]) 
```

And our database accessor is

```scala
trait UTXODb {
  def add(entry: UTXOEntry): Unit
  def get(key: OutPoint): Option[UTxOut]
}
```

There is the trival no op implementation, useful for testing

```scala
object NopUTXODb extends UTXODb {
  def add(entry: UTXOEntry): Unit = {}
  def get(key: OutPoint): Option[UTxOut] = None
}
```

and an implementation using an in memory hash table and a Leveldb backed one.

The in memory version is laid over another database and has the difference with the underlying database. It's used 
when we make modifications to a database but aren't sure if they are permanent. It is easy to cancel all the changes
by simply dropping the in memory database. The in memory version needs to keep special entries for the deleted rows
so that it can differenciate between a row it doesn't have and should check in the underlying database, from a row
that was actually deleted.

```scala
class InMemUTXODb(underlyingDb: UTXODb) extends UTXODb {
  val log = LoggerFactory.getLogger(getClass)
  val map = new mutable.HashMap[WHash, Array[Byte]]
  override def add(entry: UTXOEntry): Unit = {
    val k = new WHash(entry.key.toByteString().toArray)
    entry.value match {
      case Some(e) =>
        log.debug(s"Adding ${entry.key}")
        map.put(k, e.toByteString().toArray)
      case None =>
        log.debug(s"Removing ${entry.key}")
        map.put(k, Array.empty)
    }
  }
  def get(key: OutPoint): Option[UTxOut] = {
    val k = new WHash(key.toByteString().toArray)
    val v = map.get(k)

    v.fold(underlyingDb.get(key)) { v =>
      if (!v.isEmpty)
        Some(UTxOut.parse(ByteString(v).iterator))
      else
        None
    }
  }
  def clear() = map.clear()
}
```

Next: Let's look at the [undo blocks]({{site.baseurl}}/utxo/undo.html)
