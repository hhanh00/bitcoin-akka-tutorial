package org.bitcoinakka

import java.io.File

import language.postfixOps
import akka.util.{ByteIterator, ByteStringBuilder, ByteString}
import BitcoinMessage._
import org.fusesource.leveldbjni.JniDBFactory
import org.iq80.leveldb.{DBException, Options}
import org.slf4j.LoggerFactory
import resource.Resource

import scala.collection.mutable

case class UTxOut(txOut: TxOut, height: Option[Int]) extends ByteOrderImplicit {
  def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.append(txOut.toByteString())
    bb.putInt(height.getOrElse(0))
    bb.result()
  }
}
object UTxOut extends ByteOrderImplicit {
  def parse(bi: ByteIterator) = {
    val txOut = TxOut.parse(bi)
    val height = bi.getInt
    UTxOut(txOut, if (height == 0) None else Some(height))
  }
}
case class UTXOEntry(key: OutPoint, value: Option[UTxOut])

trait UTXODb {
  def add(entry: UTXOEntry): Unit
  def get(key: OutPoint): Option[UTxOut]
}

object NopUTXODb extends UTXODb {
  def add(entry: UTXOEntry): Unit = {}
  def get(key: OutPoint): Option[UTxOut] = None
}

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

class LevelDbUTXO(settings: AppSettingsImpl) extends UTXODb with Resource[LevelDbUTXO] {
  val log = LoggerFactory.getLogger(getClass)
  val options = new Options()
  options.createIfMissing(true)
  val dbDir = new File(s"${settings.baseDir}/utxo")
  dbDir.mkdirs()
  val db = JniDBFactory.factory.open(dbDir, options)

  def close() = db.close()

  override def add(entry: UTXOEntry): Unit = {
    entry.value match {
      case Some(v) => db.put(entry.key.toByteString().toArray, v.toByteString().toArray)
      case None => db.delete(entry.key.toByteString().toArray)
    }
  }

  override def get(key: OutPoint): Option[UTxOut] = {
    try {
      val v = db.get(key.toByteString().toArray)
      if (v != null) {
        Some(UTxOut.parse(ByteString(v).iterator))
      }
      else None
    }
    catch {
      case dbE: DBException =>
        log.warn(dbE.toString)
        Thread.sleep(5000) // wait and retry
        get(key)
    }
  }

  override def close(r: LevelDbUTXO): Unit = close()
}

object UTXO {
  type UTXOEntryList = List[UTXOEntry]

  def ofTx(tx: Tx, isCoinbase: Boolean, height: Int): UTXOEntryList = {
    val deleted = if (!isCoinbase) tx.txIns.map(txIn => UTXOEntry(txIn.prevOutPoint, None)) else Array.empty[UTXOEntry]
    val added = tx.txOuts.zipWithIndex.map { case (txOut, i) =>
      val outpoint = OutPoint(tx.hash, i)
      val utxo = UTxOut(txOut, if (isCoinbase) Some(height) else None)
      UTXOEntry(outpoint, Some(utxo))
    }
    (deleted ++ added) toList
  }
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
}

trait UTXOOperation {
  def run(db: UTXODb): Unit
  def undo(db: UTXODb): Unit
}

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
object UTXOBlockOperation {
  val log = LoggerFactory.getLogger(getClass)
  def buildAndSaveUndoBlock(db: UTXODb, block: Block, height: Int)(implicit blockStore: BlockStore): Unit = {
    val doList = block.txs.toList.zipWithIndex.map { case (tx, i) => UTXO.ofTx(tx, i == 0, height) }
    val undoList = doList.reverse.flatMap(e => UTXO.undoOf(db, e))
    blockStore.saveUndoBlock(block.header.hash, height, undoList)
  }
}
