---
layout: page
sha: 86e009f519b9ed35052c63e87f3de778bb455526
---

# Save Blocks

The theme of this milestone 'persist' is the creation and maintenance of the UTXO database.
The goal of this full node is to relay valid transactions. In order to determine if a transaction
is valid, we need to check that, among other things, its inputs haven't been spent already.
To do so, we keep a database of previous unspent transaction outputs and we update this database
whenever we receive new blocks.

The UTXO (Unspent TranXaction Outputs) database has many entries - several million at this moment,
and though a relational database would work it would not offer to best performance for the cost.
We are not using the functionalities that make RDBMS shine: We have a single table and we only look
up entries by their key. There is no need for joins or for secondary indexes.

For these reasons, the best choice is a [key/value store][1] and we opted for [LevelDB][2]. It offers
good performance and is ported to many architectures.

This section precedes the UTXO Db and is about keeping the block data on disk as flat files. This data
is actually seldom used after being put in the UTXO Db. In some cases we may need to rollback the UTXO Db
and we need to have the original block data but it is a somewhat rare occurence (it happens once or twice
per week). Block data can be considered archive and some implementation decide to delete older data
in order to reclaim disk space (block pruning). At this moment, we aren't pruning old blocks automatically.
Manually, one can delete blocks by simply deleting the files. The node doesn't need to be notified or even
stopped.

It is possible because blocks are directly stored as individual files on disk. We have a directory specified
by `blockBaseDir` in the configuration file in which the node creates a subdirectory `blocks` and then further
subdirectories per range of 1000 blocks. For example if blockBaseDir is `/tmp`, a block of height 380124 would
be in `/tmp/blocks/380/380124`. There may be more than one block of a given height but the height of a block 
cannot change.

The class `BlockStore` takes care of reading and writing blocks to disk. There will be two kinds of files: 

- Blocks
- Undo blocks

Block files contain the payload of the block message and their file name is the block hash as a hex string.
Undo blocks have the same name but with the `.undo` extension. They contain the data needed to undo the changes
of a block. We'll look at them in more details later in the section [Create undo blocks][3].


The path of the block/undo block file comes from:

```scala
def getBlockPath(hash: Hash, height: Int, isUndo: Boolean = false): Path = {
  val baseDir = settings.blockBaseDir
  val hashString = hashToString(hash)
  val suffix = if (isUndo) ".undo" else ""
  val prefix = height / 1000
  Paths.get(baseDir, "blocks", prefix.toString, height.toString, hashString+suffix)
}
```

The rest uses our previous `parse` and `toByteBuffer` to read and write blocks.

For reading, we have two methods - the first one returns an `Option[Block]` whereas the second one returns
`Block`. The later will throw an exception if the hash and height doesn't match with any block we have.

```scala
  def loadBlockOpt(hash: Hash, height: Int) = loadBlockBytes(hash, height).map(b => Block.parse(ByteString(b)))
  def loadBlock(hash: Hash, height: Int) = loadBlockOpt(hash, height).get
```

Next: The [UTXO Db]({{site.baseurl}}/utxo/db.html)

[1]: https://en.wikipedia.org/wiki/Key-value_database
[2]: https://github.com/google/leveldb
[3]: {{site.baseurl}}/utxo/undo.html
