package org.bitcoinakka

import java.nio.ByteOrder
import java.security.MessageDigest

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
}

object BitcoinMessage {
  val magic = 0xD9B4BEF9

  def calcChecksum(data: ByteString) = {
    val hash = dsha(data.toArray[Byte])
    java.util.Arrays.copyOfRange(hash, 0, 4)
  }

  val sha256: (Array[Byte]) => Array[Byte] = { data =>
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data)
    md.digest()
  }
  val dsha = sha256 compose sha256
}
