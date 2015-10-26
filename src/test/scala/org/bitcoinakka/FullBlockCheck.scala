package org.bitcoinj.core

import java.io.{File, FileOutputStream}

import scala.language.postfixOps
import org.bitcoinakka.BitcoinMessage._
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.utils.BriefLogFormatter
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.pickling.Defaults._
import scala.pickling.binary._

trait FullBlockCheck
case class MemoryPool() extends FullBlockCheck
case class BlockCheck(hash: Hash, connects: Boolean, errors: Boolean, tipHash: Hash, tipHeight: Int, name: String) extends FullBlockCheck {
  override def toString() = s"BlockCheck(${name},${connects},${errors},${hashToString(tipHash)},${tipHeight})"
}

object BuildTestSuite extends App {
  val log = LoggerFactory.getLogger(getClass)
  BriefLogFormatter.init()
  val blockFile = new File("testBlocks.dat")
  val ruleFile = new File("testBlocks-rules.dat")
  val output = new StreamOutput(new FileOutputStream(ruleFile))
  val params = RegTestParams.get()

  val c = Context.getOrCreate(params)
  val generator = new FullBlockTestGenerator(params)
  val blockList = generator.getBlocksToTest(false, false, blockFile)

  val checkList: List[FullBlockCheck] = blockList.list flatMap { rule =>
    if (rule.isInstanceOf[BlockAndValidity]) {
      val bv = rule.asInstanceOf[BlockAndValidity]
      Some(BlockCheck(bv.blockHash.getBytes.reverse, bv.connects, bv.throwsException, bv.hashChainTipAfterBlock.getBytes.reverse, bv.heightAfterBlock, bv.ruleName))
    }
    else None
  } toList

  checkList.pickleTo(output)
}

