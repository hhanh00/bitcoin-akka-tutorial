package org.bitcoinakka

import java.net.InetSocketAddress
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant

import akka.util.{ByteIterator, ByteStringBuilder, ByteString}
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

object BitcoinMessage extends ByteOrderImplicit {
  val magic = 0xD9B4BEF9
  val version = 70001
  type Hash = Array[Byte]
  type Script = Array[Byte]

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

  implicit class ByteStringBuilderExt(bb: ByteStringBuilder) {
    def putVarString(s: String) = {
      putVarInt(s.length)
      bb.putBytes(s.getBytes)
    }
    def putScript(script: Script) = {
      putVarInt(script.length)
      bb.putBytes(script)
    }
    def putVarInt(i: Int) = {
      assert(i >= 0)
      if (i <= 0xFC)
        bb.putByte(i.toByte)
      else if (i <= 0xFFFF) {
        bb.putByte(0xFD.toByte)
        bb.putShort(i.toShort)
      }
      else {
        bb.putByte(0xFE.toByte)
        bb.putInt(i)
      }
    }
  }

  implicit class ByteStringIteratorExt(bi: ByteIterator) {
    def getVarString: String = {
      val length = getVarInt
      val s: Array[Byte] = new Array(length)
      bi.getBytes(s)
      new String(s)
    }
    def getVarInt: Int = {
      val b = bi.getByte
      b match {
        case -3 => (bi.getShort & 0x0000FFFF)
        case -2 => (bi.getInt & 0x7FFFFFFF)
        case -1 => bi.getLong.toInt
        case _ => (b.toInt & 0x000000FF)
      }
    }
    def getHash: Hash = {
      val h: Hash = new Array(32)
      bi.getBytes(h)
      h
    }
    def getScript: Script = {
      val len = bi.getVarInt
      val script: Script = new Array(len)
      bi.getBytes(script)
      script
    }
  }
}

case class Version(version: Int, services: Long, timestamp: Long, recv: Array[Byte], from: Array[Byte], nonce: Long, userAgent: String, height: Int, relay: Byte) extends BitcoinMessage {
  import BitcoinMessage.ByteStringBuilderExt
  val command = "version"
  def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.putInt(version)
    bb.putLong(services)
    bb.putLong(timestamp)
    bb.putBytes(recv)
    bb.putBytes(from)
    bb.putLong(nonce)
    bb.putVarString(userAgent)
    bb.putInt(height)
    bb.putByte(relay)
    bb.result()
  }
}
object Version extends ByteOrderImplicit {
  import BitcoinMessage.ByteStringIteratorExt
  def apply(version: Int, recv: InetSocketAddress, from: InetSocketAddress, nonce: Long, userAgent: String, height: Int, relay: Byte) = {
    val now = Instant.now
    new Version(version, 1L, now.getEpochSecond, NetworkAddress(recv).toByteArray(), NetworkAddress(from).toByteArray(), nonce, userAgent, height, relay)
  }
  def parse(bs: ByteString) = {
    val iter = bs.iterator
    val version = iter.getInt
    val services = iter.getLong
    val timestamp = iter.getLong
    val recv = new Array[Byte](26)
    iter.getBytes(recv)
    val from = new Array[Byte](26)
    iter.getBytes(from)
    val nonce = iter.getLong
    val userAgent = iter.getVarString
    val height = iter.getInt
    val relay =
      if (iter.isEmpty)
        0.toByte
      else
        iter.getByte
    Version(version, services, timestamp, recv, from, nonce, userAgent, height, relay)
  }
}

case object Verack extends BitcoinMessage {
  val command = "verack"
  def toByteString() = ByteString.empty
}

case class NetworkAddress(address: InetSocketAddress) extends ByteOrderImplicit {
  def toByteArray(): Array[Byte] = {
    val bb = new ByteStringBuilder
    bb.putLong(0)
    bb.putLong(0)
    bb.putInt(0xFFFF0000)
    bb.putBytes(address.getAddress.getAddress)
    bb.putShort(address.getPort)
    bb.result().toArray[Byte]
  }
}
