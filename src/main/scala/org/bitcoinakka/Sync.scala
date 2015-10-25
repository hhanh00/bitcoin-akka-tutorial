package org.bitcoinakka

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{StandardOpenOption, Files, Paths, Path}
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.util.{ByteStringBuilder, ByteString}
import BitcoinMessage._
import Consensus._
import UTXO.UTXOEntryList
import org.slf4j.LoggerFactory
import resource._

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scalaz.Free._
import scalaz.{StateT, EphemeralStream, Trampoline, State, OptionT}
import scalaz.syntax.monad._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.list._

case class HeaderSyncData(blockHeader: BlockHeader, height: Int, pow: BigInt)

trait SyncDataProvider {
  def getHeaders(locators: List[Hash]): Future[List[BlockHeader]]
  def downloadBlocks(hashes: List[HeaderSyncData]): Future[Unit]
}

trait SyncPersist {
  def saveBlockchain(chain: List[HeaderSyncData])
  def loadBlockchain(): Blockchain
  def setMainTip(newTip: Hash)
}

case class ForkHasLessPOW(message: String) extends Exception(message)
trait Sync { self: SyncPersist =>
  private val log = LoggerFactory.getLogger(getClass)
  implicit var blockchain: Blockchain = null
  implicit val ec: ExecutionContext
  implicit val db: UTXODb
  implicit val blockStore: BlockStore

  def synchronize(provider: SyncDataProvider): Future[Boolean] = {
    val f = for {
      headers <- provider.getHeaders(blockchain.chainRev.take(10).map(_.blockHeader.hash))
      headersSyncData = attachHeaders(headers)
      _ <- Future.successful(()) if !headersSyncData.isEmpty
      _ <- isBetter(headersSyncData)
      (goodHeaders, badHeaders) = validateHeaders(headersSyncData)
      _ <- isBetter(goodHeaders)
      _ <- provider.downloadBlocks(goodHeaders.tail)
      (goodBlocks, badBlocks, undo) = validateBlocks(goodHeaders)
      _ <- isBetter(goodBlocks).transform(identity, t => { rollback(undo); t })
    } yield {
        updateMain(goodBlocks)
        true
      }

    f recover {
      case _: java.util.NoSuchElementException => false
      case _: ForkHasLessPOW => false
    }
  }

  private def rollback(undo: List[UTXOOperation]) = undo.foreach(_.undo(db))

  private def isBetter(chain: List[HeaderSyncData]): Future[Unit] = {
    val currentPOW = blockchain.chainRev.head.pow
    val newPOW = chain.last.pow
    if (newPOW > currentPOW)
      Future.successful(())
    else
      Future.failed(new ForkHasLessPOW(s"New POW = ${newPOW}, Current POW = ${currentPOW}"))
  }

  private def attachHeaders(headers: List[BlockHeader]): List[HeaderSyncData] = {
    headers match {
      case Nil => Nil
      case head :: _ =>
        val anchor = blockchain.chainRev.find(sd => sd.blockHeader.hash.sameElements(head.prevHash))
        anchor map { anchor =>
          headers.scanLeft(anchor) { case (prev, h) =>
            HeaderSyncData(h, prev.height+1, prev.pow+h.pow)
          }
        } getOrElse Nil
    }
  }

  case class HeaderCheckData(height: Int, prevHash: Hash, timestamps: Queue[Long], prevBits: Int, now2h: Long, prevTimestamp: Long, elapsedSinceAdjustment: Long) {
    override def toString() = s"HeaderCheckData(${height},${hashToString(prevHash)},${timestamps},${prevBits},${now2h},${elapsedSinceAdjustment}})"
  }
  object HeaderCheckData {
    def now2h() = Instant.now.plus(2, ChronoUnit.HOURS).getEpochSecond
    def apply(initial: HeaderSyncData, blockchain: Blockchain): HeaderCheckData = {
      val previousTimestamps = Queue.empty[Long] ++
        blockchain.chainRev.dropWhile(sd => !sd.blockHeader.hash.sameElements(initial.blockHeader.hash)).take(11).map(_.blockHeader.timestamp.getEpochSecond).padTo(11, 0L).reverse
      val height = initial.height
      val timestamp = initial.blockHeader.timestamp.getEpochSecond
      val timestampAtAdjustment = blockchain.chainRev.filter(_.height % Consensus.difficultyAdjustmentInterval == 0).head.blockHeader.timestamp.getEpochSecond
      val elapsedSinceAdjustment = timestamp-timestampAtAdjustment
      new HeaderCheckData(height, initial.blockHeader.hash, previousTimestamps, initial.blockHeader.bits, now2h(), timestamp, elapsedSinceAdjustment)
    }
  }

  def checkHeader(header: HeaderSyncData) = StateT[Trampoline, HeaderCheckData, Boolean] { checkData =>
    val height = checkData.height+1
    val blockHeader = header.blockHeader
    val prevHashMatch = checkData.prevHash.sameElements(header.blockHeader.prevHash)
    val median = checkData.timestamps.sorted.apply(5)
    val timestamp = blockHeader.timestamp.getEpochSecond
    val timestampMatch = median < timestamp && timestamp < checkData.now2h

    var updatedCheckData = checkData copy (height = height, prevHash = blockHeader.hash,
      timestamps = checkData.timestamps.tail :+ timestamp, prevBits = blockHeader.bits,
      elapsedSinceAdjustment = checkData.elapsedSinceAdjustment+(timestamp-checkData.prevTimestamp),
      prevTimestamp = timestamp)

    val hashMatch = BigInt(1, blockHeader.hash.reverse) < blockHeader.target
    val versionMatch = true // blockHeader.version <= 4 // TODO T83
    val bitsMatch = if (height >= Blockchain.firstDifficultyAdjustment && height % difficultyAdjustmentInterval == 0) {
      updatedCheckData = updatedCheckData copy (elapsedSinceAdjustment = 0L)
      blockHeader.bits == adjustedDifficulty(blockHeader, BlockHeader.getTarget(checkData.prevBits), checkData.elapsedSinceAdjustment)
    }
    else (blockHeader.bits == checkData.prevBits)

    val result = prevHashMatch && timestampMatch && hashMatch && versionMatch && bitsMatch
    if (!result) {
      log.info(s"${checkData}")
      log.info(s"Block timestamp ${timestampToString(timestamp)}(${timestamp}}) must be between ${timestampToString(median)}(${median}}) and ${timestampToString(checkData.now2h)}")
      log.info(s"Result ${result} (${prevHashMatch} ${timestampMatch} ${hashMatch} ${versionMatch} ${bitsMatch})")
    }

    Trampoline.done(updatedCheckData, result)
  }

  type HeaderCheckStateTrampoline[T] = StateT[Trampoline, HeaderCheckData, T]
  private def validateHeaders(headers: List[HeaderSyncData])(implicit blockchain: Blockchain): (List[HeaderSyncData], List[HeaderSyncData]) = {
    log.info(s"+ validateHeaders ${headers}")
    assert(!headers.isEmpty)
    val anchor = headers.head
    val r = headers.tail.spanM[HeaderCheckStateTrampoline](checkHeader).eval(HeaderCheckData(anchor, blockchain)).run
    val r2 = (anchor :: r._1, r._2)
    log.debug(s"-validateHeaders ${r2}")
    r2
  }

  case class BlockCheckData(txLog: List[UTXOOperation])
  object BlockCheckData {
    def apply(initial: HeaderSyncData, currentChainRev: List[HeaderSyncData]) = {
      val undoList = currentChainRev
        .takeWhile(sd => !sd.blockHeader.hash.sameElements(initial.blockHeader.hash))
        .map(sd => UTXOBlockOperation(sd.blockHeader.hash, sd.height, isUndo = true))
      undoList.foreach(_.run(db))
      new BlockCheckData(undoList.reverse)
    }
  }
  type BlockCheckStateTrampoline[T] = StateT[Trampoline, BlockCheckData, T]
  type BlockCheckState[T] = State[BlockCheckData, T]
  type BlockCheckStateOption[T] = OptionT[BlockCheckState, T]

  private def validateBlocks(headers: List[HeaderSyncData]): (List[HeaderSyncData], List[HeaderSyncData], List[UTXOOperation]) = {
    log.info(s"+ validateBlocks ${headers}")
    val anchor = headers.head
    val (blockCheckData, r) = headers.tail.spanM[BlockCheckStateTrampoline](checkBlockData).run(BlockCheckData(anchor, blockchain.chainRev)).run
    log.debug(s"-validateBlocks ${r}")
    (anchor :: r._1, r._2, blockCheckData.txLog)
  }

  private def checkBlockData(header: HeaderSyncData): StateT[Trampoline, BlockCheckData, Boolean] = {
    StateT[Trampoline, BlockCheckData, Boolean] { checkData =>
      log.info(s"++ checkBlockData ${header}")
      val block = blockStore.loadBlock(header.blockHeader.hash, header.height)
      val r = for {
        _ <- checkBlock(block)
        _ <- checkBlockContents(block, header.height)
      } yield ()
      val (updatedCheckData, result) = r.run(checkData)
      Trampoline.done((updatedCheckData, result.isDefined))
      }
    }

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

  private def checkBlockContents(block: Block, height: Int) = {
    val x: BlockCheckState[Option[Unit]] = State[BlockCheckData, Option[Unit]] { checkData =>
      val utxoDb = new InMemUTXODb(db)

      val feesAndSighashCheckCount = block.txs.zipWithIndex.map { case (tx, i) =>
        val fee = Consensus.checkTx(tx, i, height, block.header.timestamp.getEpochSecond, utxoDb)
        fee foreach { _ =>
          val utxoEntries = UTXO.ofTx(tx, i == 0, height)
          utxoEntries.foreach(utxoDb.add)
        }
        fee
      }

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

    OptionT.optionT(x)
  }

  private def updateMain(chain: List[HeaderSyncData]) = {
    chain match {
      case anchor :: newChain =>
        val chainRev = newChain.reverse ++ blockchain.chainRev.drop(blockchain.currentTip.height-anchor.height)
        blockchain = blockchain copy (chainRev = chainRev.take(Consensus.difficultyAdjustmentInterval))
        saveBlockchain(newChain)
        setMainTip(blockchain.currentTip.blockHeader.hash)
        log.info(s"Height = ${blockchain.currentTip.height}, POW = ${blockchain.currentTip.pow}")
      case _ => assert(false)
    }
  }

  private def updateUTXODb(block: Block, height: Int): UTXOBlockOperation = {
    UTXOBlockOperation.buildAndSaveUndoBlock(db, block, height)
    val op = UTXOBlockOperation(block.header.hash, height, false)
    op.run(db)
    op
  }
}

trait SyncPersistDb extends SyncPersist {
  private val log = LoggerFactory.getLogger(getClass)
  val connection: Connection
  lazy val saveHeaderSyncStatement = connection.prepareStatement(
    "INSERT IGNORE INTO headers(hash, header, height, pow) VALUES (?, ?, ?, ?)")
  def saveBlockchain(chain: List[HeaderSyncData]) = {
    val paddedPowBytes: Array[Byte] = new Array(32)
    for { h <- chain } {
      saveHeaderSyncStatement.setBytes(1, h.blockHeader.hash)
      saveHeaderSyncStatement.setBytes(2, h.blockHeader.toByteString().toArray)
      saveHeaderSyncStatement.setInt(3, h.height)
      val powBytes = h.pow.toByteArray
      java.util.Arrays.fill(paddedPowBytes, 0, 32, 0.toByte)
      Array.copy(powBytes, 0, paddedPowBytes, 32-powBytes.length, powBytes.length)
      saveHeaderSyncStatement.setBytes(4, paddedPowBytes)
      saveHeaderSyncStatement.addBatch()
    }
    saveHeaderSyncStatement.executeBatch()
    connection.commit()
  }

  def setMainTip(newTip: Hash) = {
    log.info(s"New tip = ${hashToString(newTip)}")

    managed(connection.prepareStatement("INSERT INTO properties(name, value) VALUES ('main', ?) " +
      "ON DUPLICATE KEY UPDATE value = VALUES(value)")).acquireAndGet { s =>
      s.setString(1, hashToString(newTip))
      s.execute()
    }
    connection.commit()
  }

  lazy val selectHeaderSyncStatement = connection.prepareStatement(
    "SELECT header, height, pow FROM headers WHERE hash = ?")
  def getHeaderSync(hash: Hash): Option[HeaderSyncData] = {
    selectHeaderSyncStatement.setBytes(1, hash)
    (for {
      rs <- managed(selectHeaderSyncStatement.executeQuery())
    } yield rs) acquireAndGet { rs =>
      if (rs.next()) {
        val headerBytes = rs.getBytes(1)
        val header = BlockHeader.parse(ByteString(headerBytes).iterator, hash, false)
        val height = rs.getInt(2)
        val powBytes = rs.getBytes(3)
        val pow = BigInt(powBytes)
        Some(HeaderSyncData(header, height, pow))
      }
      else None
    }
  }
  def loadBlockchain(): Blockchain = {
    var hash: Hash = null
    (for {
      s <- managed(connection.createStatement())
      rs <- managed(s.executeQuery("SELECT value FROM properties WHERE name='main'"))
    } yield rs) acquireAndGet { rs =>
      while (rs.next()) {
        val hashString = rs.getString(1)
        hash = hashFromString(hashString)
      }
    }

    val chainRev =
      if (hash == null) {
        val genesisChain = List(HeaderSyncData(Blockchain.genesisBlockHeader, 0, Blockchain.genesisBlockHeader.pow))
        saveBlockchain(genesisChain)
        setMainTip(Blockchain.genesisHash.array)
        genesisChain
      }
      else {
        EphemeralStream.unfold(hash)(h => getHeaderSync(h).map(hsd => (hsd, hsd.blockHeader.prevHash))).take(Consensus.difficultyAdjustmentInterval).toList
      }
    Blockchain(chainRev)
  }
}

class BlockStore(settings: AppSettingsImpl) {
  val log = LoggerFactory.getLogger(getClass)

  def getBlockPath(hash: Hash, height: Int, isUndo: Boolean = false): Path = {
    val baseDir = settings.blockBaseDir
    val hashString = hashToString(hash)
    val suffix = if (isUndo) ".undo" else ""
    val prefix = height / 1000
    Paths.get(baseDir, "blocks", prefix.toString, height.toString, hashString+suffix)
  }

  def haveBlock(hash: Hash, height: Int): Boolean = {
    val path = getBlockPath(hash, height)
    Files.exists(path)
  }

  def saveBlock(block: Block, height: Int) = {
    val path = getBlockPath(block.header.hash, height)
    path.toFile.getParentFile.mkdirs()
    managed(FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)).foreach { _.write(block.payload.toByteBuffer) }
  }

  def loadBlockBytes(hash: Hash, height: Int): Option[Array[Byte]] = {
    val path = getBlockPath(hash, height)
    Files.exists(path).option(()).map { _ =>
      managed(FileChannel.open(path, StandardOpenOption.READ)).acquireAndGet { channel =>
        val bb = ByteBuffer.allocate(channel.size.toInt)
        channel.read(bb)
        channel.close()
        bb.array()
      }
    }
  }

  def loadBlockOpt(hash: Hash, height: Int) = loadBlockBytes(hash, height).map(b => Block.parse(ByteString(b)))
  def loadBlock(hash: Hash, height: Int) = loadBlockOpt(hash, height).get

  def saveUndoBlock(hash: Hash, height: Int, undoList: UTXOEntryList): Unit = {
    val path = getBlockPath(hash, height, true)
    path.toFile.getParentFile.mkdirs()
    val channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    val bb = new ByteStringBuilder
    bb.putInt(undoList.size)
    undoList.foreach { entry =>
      log.debug(s"Undo Write> ${entry.key}")
      bb.append(entry.key.toByteString())
      bb.putByte(if (entry.value.isDefined) 1.toByte else 0.toByte)
      entry.value.foreach { v => bb.append(v.toByteString()) }
    }
    channel.write(bb.result().toByteBuffer)
    channel.close()
  }

  def loadUndoBlock(hash: Hash, height: Int): UTXOEntryList = {
    val path = getBlockPath(hash, height, true)
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    val bb = ByteBuffer.allocate(channel.size.toInt)
    channel.read(bb)
    channel.close()
    bb.flip()
    val bi = ByteString(bb).iterator
    val size = bi.getInt
    List.range(0, size).map { _ =>
      val key = OutPoint.parse(bi)
      log.debug(s"Undo Read> ${key}")
      val hasValue = bi.getByte
      val value =
        if (hasValue != 0.toByte)
          Some(UTxOut.parse(bi))
        else
          None
      UTXOEntry(key, value)
    }
  }
}
