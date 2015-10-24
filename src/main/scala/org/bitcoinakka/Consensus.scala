package org.bitcoinakka

import java.time.{ZoneId, Instant}

import org.slf4j.LoggerFactory

object Consensus {
  val log = LoggerFactory.getLogger(getClass)
  val difficultyAdjustmentInterval = 2016
  val targetElapsed = 600*difficultyAdjustmentInterval // 10 mn average per block

  def adjustedDifficulty(blockHeader: BlockHeader, prevTarget: BigInt, elapsedSinceAdjustment: Long)(implicit blockchain: Blockchain): Int = {
    val boundedElapsed = (elapsedSinceAdjustment min targetElapsed*4) max targetElapsed/4
    val adjustedTarget = prevTarget*boundedElapsed/targetElapsed
    val bits = BlockHeader.getBits(adjustedTarget)
    log.debug(s"Old bits = ${BlockHeader.getBits(prevTarget)}, New bits = ${bits}")
    bits
  }

  def timestampToString(timestamp: Long) = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
}
