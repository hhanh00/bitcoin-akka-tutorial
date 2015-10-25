package org.bitcoinakka

import java.nio.ByteOrder
import java.time.Instant

import akka.util.ByteString
import BitcoinMessage._
import org.apache.commons.codec.binary.Hex

import scalaz.EphemeralStream

case class MessageHeader(command: String, length: Int, checksum: Array[Byte], payload: ByteString) {
  val totalLength = 24 + length
}

trait MessageHandler {
  def parseMessageHeader(bs: ByteString): Option[(MessageHeader, ByteString)] = {
    if (bs.length < 24)  // got less than the length of the message header, stop
      None
    else {
      val bi = bs.iterator
      val magic = bi.getInt
      val command: Array[Byte] = new Array(12)
      bi.getBytes(command)
      val length = bi.getInt
      val checksum: Array[Byte] = new Array(4)
      bi.getBytes(checksum)
      val totalLength = 24 + length
      if (bs.length >= totalLength) {
        // got enough for a message
        val payload = bi.take(bs.length).toByteString
        val mh = MessageHeader(new String(command).trim(), length, checksum, payload.take(length))
        Some(mh, bs.drop(totalLength))
      }
      else None
    }
  }

  var buffer = ByteString.empty
  def frame(data: ByteString): List[MessageHeader] = {
    buffer ++= data

    val messages = EphemeralStream.unfold(buffer)(parseMessageHeader).toList
    buffer = buffer.drop(messages.map(_.totalLength).sum)
    messages
  }
}

trait BitcoinNetParams {
  val magic: Int
  val genesisHash: WHash
  val genesisBlockHeader: BlockHeader
  val firstDifficultyAdjustment: Int
}
object MainNet extends BitcoinNetParams {
  val magic = 0xD9B4BEF9
  val genesisHash = hashFromString("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f").wrapped
  val genesisBytes = Hex.decodeHex("0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c".toCharArray)
  val genesisBlockHeader = BlockHeader.parse(ByteString(genesisBytes).iterator, genesisHash.array, false)
  val firstDifficultyAdjustment = 32256
}
object RegTestNet extends BitcoinNetParams {
  val magic = 0xDAB5BFFA
  val genesisHash = new WHash(hashFromString("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"))
  val genesisBlockHeader = BlockHeader(genesisHash.array, 1, zeroHash, hashFromString("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"),
    Instant.ofEpochSecond(1296688602), 545259519, 2, 0)
  val firstDifficultyAdjustment = 0
}
case class Blockchain(chainRev: List[HeaderSyncData]) {
  def currentTip = chainRev.head
  val chain = chainRev.reverse
}
object Blockchain {
  val zeroHash = new WHash(new Array(32))

  val net = System.getProperty("net", "main")
  val netParams = net match {
    case "main" => MainNet
    case "regtest" => RegTestNet
  }

  val magic = netParams.magic
  val genesisHash = netParams.genesisHash
  val genesisBlockHeader = netParams.genesisBlockHeader
  val firstDifficultyAdjustment = netParams.firstDifficultyAdjustment
}
