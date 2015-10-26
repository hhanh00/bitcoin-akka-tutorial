package org.bitcoinakka

import java.nio.{ByteBuffer, ByteOrder}
import java.time.{Instant, ZoneId}

import akka.util.ByteString
import org.bitcoinakka.BitcoinMessage._
import org.slf4j.LoggerFactory

import scalaz.syntax.std.boolean._

object Consensus {
  val log = LoggerFactory.getLogger(getClass)
  val difficultyAdjustmentInterval = 2016
  val targetElapsed = 600*difficultyAdjustmentInterval // 10 mn average per block
  val maxBlockSize = 1000000
  val coinbaseMaturity = 100
  val maxSighashCheckCount = 20000

  def adjustedDifficulty(blockHeader: BlockHeader, prevTarget: BigInt, elapsedSinceAdjustment: Long)(implicit blockchain: Blockchain): Int = {
    val boundedElapsed = (elapsedSinceAdjustment min targetElapsed*4) max targetElapsed/4
    val adjustedTarget = prevTarget*boundedElapsed/targetElapsed
    val bits = BlockHeader.getBits(adjustedTarget)
    log.debug(s"Old bits = ${BlockHeader.getBits(prevTarget)}, New bits = ${bits}")
    bits
  }

  def checkSize(block: Block):Option[Unit] = {
    val x = (block.toByteString().length <= maxBlockSize).option(())
    log.debug(s"checkSize: ${x}")
    x
  }

  def merkleRoot(hashes: Array[Hash]): Hash = {
    if (hashes.length == 1)
      hashes(0)
    else {
      val hs =
        if (hashes.length % 2 == 0)
          hashes
        else
          hashes :+ hashes.last
      val nextLevelHashes = hs.sliding(2, 2).map { case Array(a, b) =>
        BitcoinMessage.dsha(a ++ b)
      }.toArray
      merkleRoot(nextLevelHashes)
    }
  }

  def checkMerkleRoot(block: Block): Option[Unit] = {
    val blockRoot = merkleRoot(block.txs.map(_.hash))
    (new WHash(blockRoot) == new WHash(block.header.merkleRoot)).option(())
  }

  def getBlockReward(height: Int): Long = {
    val range = height / 210000
    val era = range min 33
    5000000000L / (1L << era)
  }

  def checkDoubleSpend(tx: Tx, index: Int, db: UTXODb): Option[Long] = {
    if (index != 0) {
      val inputValues = tx.txIns.zipWithIndex.map { case (txIn, i) =>
        db.get(txIn.prevOutPoint).map(_.txOut.value)
      }
      if (inputValues.forall(_.isDefined)) {
        val totalInputs = inputValues.flatten.sum
        val totalOutputs = tx.txOuts.map(_.value).sum
        val fee = totalInputs-totalOutputs
        Some(fee)
      }
      else {
        if (index > 0)
          log.error(s"Double spend in tx ${hashToString(tx.hash)}")
        None
      }
    }
    else Some(0L)
  }

  def checkScripts(tx: Tx, height: Int, db: UTXODb): Option[Int] = {
    val txBytes = tx.toByteString().toArray
    val r = for {
      (txIn, index) <- tx.txIns.zipWithIndex
      prevOutPoint = txIn.prevOutPoint
      utxo <- db.get(prevOutPoint)
      utxoHeight = utxo.height.getOrElse(-coinbaseMaturity)
    } yield {
        (height-utxoHeight >= coinbaseMaturity).option(()) flatMap { _ =>
          val result = Consensus.verifyScript(utxo.txOut.script, txBytes, index, 5 /* BIP16 && 66 */)
          if (result == 1)
            Some(ScriptUtils.sighashCheckCountInput(utxo.txOut.script, txIn.sigScript))
          else {
            log.error(s"Script verification failed in tx ${tx}")
            None
          }
        }
      }
    if (r.forall(_.isDefined)) {
      val sighashCheckCountOfInputs = r.map(_.get).sum
      val sighashCheckCountOfOutputs = tx.txOuts.map(txOut => ScriptUtils.sighashCheckCount1(txOut.script)).sum
      log.debug(s"Sighash check count for ${hashToString(tx.hash)} = ${sighashCheckCountOfInputs} + ${sighashCheckCountOfOutputs}")
      Some(sighashCheckCountOfInputs+sighashCheckCountOfOutputs)
    }
    else
      None
  }

  def bip34(script: => Script, height: Int): Option[Boolean] = {
    for { _ <- (script.length >= 2 && script.length <= 100).option(()) } yield {
      if (Blockchain.checkBip34) {
        val len = script(0)
        val bb = ByteBuffer.allocate(4)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.put(script, 1, len)
        bb.flip()
        bb.limit(4)
        bb.getInt == height
      }
      else true
    }
  }

  def checkCoinbase(tx: Tx, height: Int, fees: Long): Option[Unit] = {
    val totalOutputs = tx.txOuts.map(_.value).sum
    val feesMatch = totalOutputs <= fees+getBlockReward(height)
    if (!feesMatch) {
      log.debug(s"Fees don't match: coinbase ${fees+getBlockReward(height)} vs ${totalOutputs}")
    }
    val coinbaseFormatMatch = tx.txIns.length == 1 && tx.txIns(0).prevOutPoint.hash.sameElements(zeroHash) &&
      tx.txIns(0).prevOutPoint.index == -1
    log.debug(s"feesMatch: ${feesMatch} coinbaseFormatMatch: ${coinbaseFormatMatch}")
    (feesMatch && coinbaseFormatMatch && bip34(tx.txIns(0).sigScript, height).getOrElse(false)).option(())
  }

  def isFinal(tx: Tx, height: Int, timestamp: Long) = {
    val sequenceFinal = tx.txIns.exists(txIn => txIn.sequence == 0xFFFFFFFF)
    val lockTime = tx.lockTime & 0x7FFFFFFF
    (sequenceFinal || lockTime == 0 || (lockTime < 500000000 && height >= lockTime) || (lockTime >= 500000000 && timestamp >= lockTime))
  }

  def checkTx(tx: Tx, index: Int, height: Int, timestamp: Long, db: UTXODb): Option[(Long, Int)] = {
    for {
      sighashCheckCount <- checkScripts(tx, height, db)
      fees <- checkDoubleSpend(tx, index, db)
      _ <- (isFinal(tx, height, timestamp)).option(())
    } yield (fees, sighashCheckCount)
  }

  def timestampToString(timestamp: Long) = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())

  @native def verifyScript(scriptPubKey: Script, tx: Array[Byte], index: Int, flags: Int): Int
}

object ScriptUtils extends ByteOrderImplicit {
  val log = LoggerFactory.getLogger(getClass)
  def sighashCheckCount1(script: Script): Int = {
    var count = 0
    var multisigWeight = 20
    val bi = ByteString(script).iterator

    try {
      while (!bi.isEmpty) {
        val next: Int = bi.getByte & 0x000000FF
        if (next >= 82 && next <= 96) {
          multisigWeight = next - 80
        }
        else {
          if (next == 172 || next == 173)
            count += 1
          else if (next == 174 || next == 175)
            count += multisigWeight
          multisigWeight = 20
          if (next >= 1 && next <= 75)
            bi.drop(next)
          else if (next == 76) {
            val len: Int = bi.getByte & 0x000000FF
            bi.drop(len)
          }
          else if (next == 77) {
            val len: Int = bi.getShort & 0x0000FFFF
            bi.drop(len)
          }
          else if (next == 78) {
            val len: Int = bi.getInt & 0x7FFFFFFF
            bi.drop(len)
          }
        }
      }
    }
    catch {
      case t: NoSuchElementException =>
    }
    count
  }

  def getLastPushData(script: Script): Option[Script] = {
    val bi = ByteString(script).iterator
    var lastData: Option[Script] = None

    def getNextBytes(n: Int): Script = {
      val ba: Script = new Array(n)
      bi.getBytes(ba)
      ba
    }

    try {
      while (!bi.isEmpty) {
        val next: Int = bi.getByte & 0x000000FF
        if (next >= 1 && next <= 75)
          lastData = Some(getNextBytes(next))
        else if (next == 76) {
          val len: Int = bi.getByte & 0x000000FF
          lastData = Some(getNextBytes(len))
        }
        else if (next == 77) {
          val len: Int = bi.getShort & 0x0000FFFF
          lastData = Some(getNextBytes(len))
        }
        else if (next == 78) {
          val len: Int = bi.getInt
          lastData = Some(getNextBytes(len))
        }
        else
          lastData = None
      }
    }
    catch {
      case t: NoSuchElementException =>
    }
    lastData
  }

  def isP2SH(script: Script) = script.length == 23 && script(0) == -87 && script(1) == 20 && script(22) == -121

  def getReedeemScript(pubScript: Script, sigScript: Script): Option[Script] = {
    for {
      _ <- (isP2SH(pubScript)).option(())
      data <- getLastPushData(sigScript)
    } yield data
  }

  def sighashCheckCountInput(pubScript: Script, sigScript: Script) = getReedeemScript(pubScript, sigScript).map(sighashCheckCount1).getOrElse(0)
}