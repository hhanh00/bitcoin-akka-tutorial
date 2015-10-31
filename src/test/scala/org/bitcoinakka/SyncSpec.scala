package org.bitcoinakka

import java.io.{File, FileInputStream}
import java.sql.{Connection, DriverManager}

import akka.actor.ActorSystem
import BitcoinMessage._
import org.bitcoinj.core.{BlockCheck, FullBlockCheck}
import org.scalatest.{FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.pickling.Defaults._
import scala.pickling.binary.{BinaryPickle, StreamInput, _}
import scalaz.StreamT

class TestSync(val appSettings: AppSettingsImpl, _db: UTXODb, blocksIterator: Iterator[Block])
  extends Sync with SyncPersistDb with SyncDataProvider {
  val log = LoggerFactory.getLogger(getClass)
  var testBlockchain: List[WHash] = null
  var blockchainSet: Set[WHash] = null
  var blocksSeen = Map.empty[WHash, Block]

  blockchain = loadBlockchain()
  implicit val ec = ExecutionContext.Implicits.global
  override def downloadBlocks(headers: List[HeaderSyncData]): Future[Unit] = {
    for {
      h <- headers
    } {
        val block = blocksSeen(new WHash(h.blockHeader.hash))
        blockStore.saveBlock(block, h.height)
    }
    Future.successful(())
  }
  override def getHeaders(locators: List[Hash]): Future[List[BlockHeader]] = {
    val locator = locators.find(locator => blockchainSet.contains(new WHash(locator)))
    val hs = locator map { locator => testBlockchain.dropWhile(_ != new WHash(locator)).tail } getOrElse testBlockchain

    Future.successful(for {h <- hs} yield {
      val block = blocksSeen(h)
      block.header
    })
  }

  def add(bc: BlockCheck) = {
    log.info("***********************")
    log.info(s"BlockCheck ${bc.name} ${hashToString(bc.hash)}")
    log.info("***********************")
    val wHash = new WHash(bc.hash)
    val nextBlock = blocksIterator.next()
    blocksSeen += wHash -> nextBlock
    testBlockchain = getBlockchain(bc.hash)
    blockchainSet = testBlockchain.toSet
  }

  def getBlockchain(tip: Hash): List[WHash] = getBlockchainEndingAt(tip) ++ getBlockchainStartingFrom(tip)

  def getBlockchainEndingAt(tip: Hash): List[WHash] = {
    StreamT.unfold(tip) { h =>
      val wh = new WHash(h)
      blocksSeen.get(wh) map { block =>
        val ph = block.header.prevHash
        (wh, ph)
      }
    }.toStream.toList.reverse
  }

  def getBlockchainStartingFrom(head: Hash): List[WHash] = {
    StreamT.unfold(head) { h =>
      val wh = new WHash(h)
      blocksSeen
        .find { case (_, bh) => new WHash(bh.header.prevHash) == wh }
        .map { case (nh, nb) => (nh, nh.array) }
    }.toStream.toList
  }

  implicit val db = _db
  implicit val blockStore: BlockStore = new BlockStore(appSettings)
}

class SyncSpec extends FlatSpec with Matchers {
  val log = LoggerFactory.getLogger(getClass)
  Class.forName("com.mysql.jdbc.Driver")
  System.loadLibrary("consensus-jni")
  val system = ActorSystem()
  implicit val settings = AppSettings(system)
  implicit val ec = system.dispatcher

  val db = new InMemUTXODb(NopUTXODb)

  val blockFile = new File("testBlocks.dat")
  val ruleFile = new File("testBlocks-rules.dat")
  val input = new StreamInput(new FileInputStream(ruleFile))
  val p = BinaryPickle(input)

  val bcf = new BlockchainFile
  val blocksIterator = bcf.parse(blockFile).toIterator
  val checkList = p.unpickle[List[FullBlockCheck]]

  val sync = new TestSync(settings, db, blocksIterator)

  "Sync trait" should "run regression tests" in {
    for { fbc <- checkList } {
      fbc match {
        case bc: BlockCheck =>
          sync.add(bc)
          val f = sync.synchronize(sync)
          val _ = Await.result(f, 1.minute)
          val tip = sync.blockchain.currentTip.blockHeader.hash
          log.info(s"Expected Tip = ${hashToString(bc.tipHash)}")
          log.info(s"         Tip = ${hashToString(tip)}")
          if (!bc.tipHash.sameElements(tip))
            log.error("************* MISMATCH ******************")
      }
    }
  }
}
