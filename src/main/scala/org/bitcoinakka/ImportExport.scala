package org.bitcoinakka

import java.io.{FileOutputStream, FileInputStream, File}
import java.sql.DriverManager

import akka.util.{ByteStringBuilder, ByteString}
import com.typesafe.config.ConfigFactory
import BitcoinMessage._
import org.slf4j.LoggerFactory
import resource._

import scala.collection.mutable

class BlockchainFile {
  val log = LoggerFactory.getLogger(getClass)
  val config = ConfigFactory.load()
  val settings = new AppSettingsImpl(config)
  implicit val blockStore = new BlockStore(settings)
  val lastBlocks = mutable.Queue.empty[WHash]
  val db = new SyncPersistDb {
    val appSettings = settings
  }

  def r() = {
    var lastHeader: HeaderSyncData = null
    var pow: BigInt = 0
    var bIndex = settings.start-1
    for {utxoDb <- managed(new LevelDbUTXO(settings))} {
      val fileList = settings.blockFiles.checkpoints
      val blocks = for {
        file <- fileList.toIterator
        fileName = s"${settings.blockFiles.path}/bootstrap-${file}.dat"
        block <- parse(new File(fileName))
      } yield block

      for {block <- blocks} {
        val wHash = new WHash(block.header.hash)
        if (lastBlocks.contains(wHash)) {
          log.warn(s"Duplicate block ${hashToString(wHash.array)}")
        }
        else {
          bIndex += 1
          pow += block.header.pow
          UTXOForwardOnlyBlockOperation.run(utxoDb, block, bIndex)
          val hsd = HeaderSyncData(block.header, bIndex, pow)
          lastHeader = hsd
          db.saveBlockchain(List(hsd))
          lastBlocks.enqueue(wHash)
          if (lastBlocks.size > 10)
            lastBlocks.dequeue()
        }
      }
    }
    val hash = lastHeader.blockHeader.hash
    log.info(s"Final block height = ${bIndex} ${hashToString(hash)}")
    db.setMainTip(hash)
  }

  def parse(file: File): Stream[Block] = {
    val is = new FileInputStream(file)
    val mhBytes: Array[Byte] = new Array(8)
    scalaz.Scalaz.unfold(true) { _ =>
      if (is.available() > 0) {
        is.read(mhBytes)
        val bs = ByteString(mhBytes)
        val bi = bs.iterator
        val magic = bi.getInt
        val length = bi.getInt
        val payloadBytes: Array[Byte] = new Array(length)
        is.read(payloadBytes)
        Some(Block.parse(ByteString(payloadBytes)), true)
      } else None
    }
  }

  def write(file: File)(hash: Hash, stopHeight: Int) = {
    val os = new FileOutputStream(file)
    val initial = db.getHeaderSync(hash).get
    Iterator
      .iterate(initial) { hsd => db.getHeaderSync(hsd.blockHeader.prevHash).get }
      .takeWhile(_.height > stopHeight)
      .toList
      .reverse
      .foreach { hsd =>
        log.info(s"${hsd}")
        val block = blockStore.loadBlockBytes(hsd.blockHeader.hash, hsd.height).get
        val bb = new ByteStringBuilder
        bb.putInt(0xD9B4BEF9)
        bb.putInt(block.length)
        os.write(bb.result.toArray)
        os.write(block)
      }
    os.close()
  }

  def w() = {
    val outFile = new File(settings.saveBlocksConfig.getString("file"))
    write(outFile)(hashFromString(settings.saveBlocksConfig.getString("endHash")), settings.saveBlocksConfig.getInt("startHeight"))
  }
}

object BlockchainFileRead extends App {
  val bcf = new BlockchainFile
  bcf.r()
}
object BlockchainFileWrite extends App {
  val bcf = new BlockchainFile
  bcf.w()
}
