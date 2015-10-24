package org.bitcoinakka

import java.nio.ByteOrder

import akka.util.{ByteStringBuilder, ByteString}
import org.apache.commons.lang3.StringUtils

trait ByteOrderImplicit {
  implicit val byteOrder = ByteOrder.LITTLE_ENDIAN
}

trait BitcoinMessage extends ByteOrderImplicit {
  import BitcoinMessage._
  val command: String
  def toByteString(): ByteString
  def toMessage() = {
    val bb = new ByteStringBuilder
    bb.putInt(magic)
    bb.putBytes(StringUtils.rightPad(command, 12, 0.toChar).getBytes())
    val payload = toByteString()
    bb.putInt(payload.length)
    val checksum = calcChecksum(payload)
    bb.putBytes(checksum)
    bb.append(payload)
    bb.result()
  }

  def calcChecksum(payload: ByteString) = ???
}

object BitcoinMessage {
  val magic = 0xD9B4BEF9
}
