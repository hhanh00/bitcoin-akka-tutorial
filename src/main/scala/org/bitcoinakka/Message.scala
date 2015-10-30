package org.bitcoinakka

import java.net.{Inet6Address, Inet4Address, InetAddress, InetSocketAddress}
import java.nio.{ByteBuffer, ByteOrder}
import java.security.MessageDigest
import java.time.Instant

import akka.util.{ByteIterator, ByteString, ByteStringBuilder}
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.StringUtils
import BitcoinMessage._

import scala.language.postfixOps

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
  type WHash = collection.mutable.WrappedArray.ofByte
  type Script = Array[Byte]
  val zeroHash: Hash = new Array(32)

  def hashFromString(hs: String) = Hex.decodeHex(hs.toCharArray).reverse
  def hashToString(hash: Hash) = Hex.encodeHexString(hash.reverse)

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
    def putInetSocketAddress(address: InetSocketAddress) = {
      bb.putLong(1)
      val addr = address.getAddress
      addr match {
        case _: Inet4Address =>
          bb.putLong(0)
          bb.putInt(0xFFFF0000)
        case _: Inet6Address =>
      }
      bb.putBytes(addr.getAddress)
      bb.putShort(java.lang.Short.reverseBytes(address.getPort.toShort))
    }
    def putBlockHeader(bh: BlockHeader) = {
      bb.append(bh.toByteString())
      bb.putVarInt(0)
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
    def getInetSocketAddress: InetSocketAddress = {
      bi.drop(8)
      val addrBytes: Array[Byte] = new Array(16)
      bi.getBytes(addrBytes)
      val addr = InetAddress.getByAddress(addrBytes)
      val port: Int = bi.getShort(ByteOrder.BIG_ENDIAN).toInt & 0xFFFF
      new InetSocketAddress(addr, port)
    }
    def getBlockHeader: BlockHeader = {
      val hashedPart = bi.clone.slice(0, 80).toArray[Byte]
      val blockHash = dsha(hashedPart)
      BlockHeader.parse(bi, blockHash, true)
    }
  }

  implicit class toWHash(hash: Hash) {
    def wrapped = new WHash(hash)
  }
}

class InternalBitcoinMessage(val hashes: List[Hash]) extends BitcoinMessage {
  val command = ""
  def toByteString() = ???
}
case class GetTxData(_hashes: List[Hash]) extends InternalBitcoinMessage(_hashes) {
  def toGetData() = GetData(hashes.map(InvEntry(1, _)))
}
case class GetBlockData(_hashes: List[Hash]) extends InternalBitcoinMessage(_hashes)  {
  def toGetData() = GetData(hashes.map(InvEntry(2, _)))
}

case class Block(header: BlockHeader, txs: Array[Tx], payload: ByteString) extends BitcoinMessage {
  override val command: String = "block"
  override def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.append(header.toByteString())
    bb.putVarInt(txs.length)
    txs.foreach(tx => bb.append(tx.toByteString))
    bb.result()
  }
  override def toString() = s"Block($header)"
}
object Block {
  def parse(bs: ByteString): Block = {
    val iter = bs.iterator
    val hashedPart = iter.clone.slice(0, 80).toArray
    val blockHash = dsha(hashedPart)
    val bh = BlockHeader.parse(iter, blockHash, true)
    val txs = Array.range(0, bh.txCount).map { _ => Tx.parseBI(iter) }
    Block(bh, txs, bs)
  }
}

case class Tx(hash: Hash, version: Int, txIns: List[TxIn], txOuts: List[TxOut], lockTime: Int) extends BitcoinMessage {
  val command = "tx"
  def toByteString() = {
    val itx = InternalTx(version, txIns, txOuts, lockTime)
    itx.toByteString()
  }
  override def toString() = s"Tx(${hashToString(hash)}, ${txIns.size} -> ${txOuts.size})"
}
object Tx {
  def parseBI(bi: ByteIterator) = {
    val mark = bi.len
    val biCopy = bi.clone()
    val itx: InternalTx = InternalTx.parseBI(bi)
    val txLen = mark-bi.len
    val txBytes = biCopy.slice(0, txLen).toArray
    val txHash = dsha(txBytes)
    Tx(txHash, itx.version, itx.txIns, itx.txOuts, itx.lockTime)
  }
}

case class BlockHeader(hash: Hash, version: Int, prevHash: Hash, merkleRoot: Hash, timestamp: Instant, bits: Int, nonce: Int, txCount: Int) extends BitcoinMessage {
  val command = "blockheader"
  val target = BlockHeader.getTarget(bits)
  val pow = BlockHeader.maxHash/target
  override def toByteString(): ByteString = {
    val ibh = InternalBlockHeader(version, prevHash, merkleRoot, timestamp, bits, nonce)
    ibh.toByteString() // NB: txCount is not saved!
  }
  override def toString() = s"BlockHeader(${hashToString(hash)}, ${hashToString(prevHash)})"
}

object BlockHeader {
  val maxHash = BigInt(0).setBit(256)
  def parse(bi: ByteIterator, blockHash: Hash, readTxCount: Boolean): BlockHeader = {
    val ibh: InternalBlockHeader = InternalBlockHeader.parseBI(bi)
    val txCount = if (readTxCount) bi.getVarInt else 0
    BlockHeader(blockHash, ibh.version, ibh.prevHash, ibh.merkleRoot, ibh.timestamp, ibh.bits, ibh.nonce, txCount)
  }

  def getBits(target: BigInt): Int = {
    val exp256 = target.toByteArray.length
    val mantissa = if (exp256 <= 3) // shift the 3 msb of target
      target.intValue() << (8*(3-exp256))
    else
      (target >> (8*(exp256-3))).intValue()

    if ((mantissa & 0x800000) != 0) // if this bit is set, it would be a negative number
      (mantissa>>8)|((exp256+1) << 24)
    else
      mantissa|(exp256 << 24)
  }

  def getTarget(bits: Int): BigInt = {
    val bb = ByteBuffer.allocate(4)
    bb.order(ByteOrder.BIG_ENDIAN)
    bb.putInt(bits)
    bb.flip()
    val b: Array[Byte] = new Array(4)
    bb.get(b)
    val mantissa = BigInt(1, b.slice(1, 4))
    val exp: Int = b(0).toInt & 0x000000FF
    val target = mantissa << (8*(exp-3))
    target
  }
}

case class IncomingMessage(m: BitcoinMessage) extends BitcoinMessage {
  val command = m.command
  def toByteString = m.toByteString()
}

case class OutgoingMessage(m: BitcoinMessage) extends BitcoinMessage {
  val command = m.command
  def toByteString = m.toByteString()
}

@MessageMacro case class Version(version: Int, services: Long, timestamp: Long, recv: InetSocketAddress, from: InetSocketAddress, nonce: Long, userAgent: String, height: Int)
@MessageMacro case class Verack()
@MessageMacro case class GetHeaders(version: Int, hashes: List[Hash], stopHash: Hash)
@MessageMacro case class Headers(blockHeaders: List[BlockHeader])
@MessageMacro case class InvEntry(tpe: Int, hash: Hash)
@MessageMacro case class GetData(invs: List[InvEntry])
@MessageMacro case class OutPoint(hash: Hash, index: Int)
@MessageMacro case class TxIn(prevOutPoint: OutPoint, sigScript: Script, sequence: Int)
@MessageMacro case class TxOut(value: Long, script: Script)
@MessageMacro case class InternalTx(version: Int, txIns: List[TxIn], txOuts: List[TxOut], lockTime: Int)
@MessageMacro case class InternalBlockHeader(version: Int, prevHash: Hash, merkleRoot: Hash, timestamp: Instant, bits: Int, nonce: Int)
@MessageMacro case class Inv(invs: List[InvEntry])
@MessageMacro case class GetAddr()
@MessageMacro case class Addr(addrs: List[AddrEntry])
@MessageMacro case class AddrEntry(timestamp: Instant, addr: InetSocketAddress)
@MessageMacro case class Ping(nonce: Long)
@MessageMacro case class Pong(nonce: Long)
