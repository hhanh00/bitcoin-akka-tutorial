package org.bitcoinakka

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{StandardOpenOption, Files, Paths, Path}
import java.sql.Connection

import akka.util.ByteString
import org.bitcoinakka.BitcoinMessage._
import org.slf4j.LoggerFactory
import resource._

import scala.concurrent.{ExecutionContext, Future}
import scalaz.EphemeralStream
import scalaz.syntax.std.boolean._

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

trait Sync { self: SyncPersist =>
  private val log = LoggerFactory.getLogger(getClass)
  implicit var blockchain: Blockchain = null
  implicit val ec: ExecutionContext

  def synchronize(provider: SyncDataProvider): Future[Boolean] = {
    val f = for {
      headers <- provider.getHeaders(blockchain.chainRev.take(10).map(_.blockHeader.hash))
      headersSyncData = attachHeaders(headers)
      _ <- Future.successful(()) if !headersSyncData.isEmpty
      _ <- provider.downloadBlocks(headersSyncData.tail)
    } yield {
        updateMain(headersSyncData)
        true
      }

    f recover {
      case _: java.util.NoSuchElementException => false
    }
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
}
