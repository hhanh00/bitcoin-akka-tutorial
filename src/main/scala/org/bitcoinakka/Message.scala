package org.bitcoinakka

import java.nio.ByteOrder
import java.security.MessageDigest

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
