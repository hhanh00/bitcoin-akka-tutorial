package org.bitcoinakka

import scala.language.postfixOps

import java.net.{InetAddress, InetSocketAddress}
import java.nio.{ByteBuffer, ByteOrder}
import java.security.MessageDigest
import java.time.Instant

import akka.util.{ByteIterator, ByteStringBuilder, ByteString}
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.StringUtils
import BitcoinMessage._

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

  implicit class toWHash(hash: Hash) {
    def wrapped = new WHash(hash)
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

case class GetHeaders(hashes: List[Hash], stopHash: Hash) extends BitcoinMessage {
  override val command: String = "getheaders"
  override def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.putInt(version)
    bb.putVarInt(hashes.length)
    for { h <- hashes} {
      bb.putBytes(h)
    }
    bb.putBytes(stopHash)
    bb.result()
  }
}
object GetHeaders {
  def parse(bs: ByteString): GetHeaders = {
    val iter = bs.iterator
    iter.getInt
    val count = iter.getVarInt
    val hashes = (for { _ <- 0 until count } yield {
      iter.getHash
    }) toList
    val stopHash = iter.getHash

    GetHeaders(hashes, stopHash)
  }
}

case class Headers(blockHeaders: List[BlockHeader]) extends BitcoinMessage {
  override val command: String = "headers"
  override def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.putVarInt(blockHeaders.length)
    for { bh <- blockHeaders } {
      bb.append(bh.toByteString())
      bb.putVarInt(0)
    }
    bb.result()
  }
  def isEmpty = blockHeaders.isEmpty
}
object Headers {
  def parse(bs: ByteString): Headers = {
    val iter = bs.iterator
    val count = iter.getVarInt
    val bhs = (for { _ <- 0 until count } yield {
      val hashedPart = iter.clone.slice(0, 80).toArray[Byte]
      val blockHash = dsha(hashedPart)
      BlockHeader.parse(iter, blockHash, true)
    }) toList

    Headers(bhs)
  }
}

class GetData(tpe: Int, hashes: List[Hash]) extends BitcoinMessage {
  override val command: String = "getdata"
  override def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.putVarInt(hashes.length)
    for { h <- hashes} {
      bb.putInt(tpe)
      bb.putBytes(h)
    }
    bb.result()
  }
}
object GetData {
  def parse(bs: ByteString): (GetTxData, GetBlockData) = {
    val iter = bs.iterator
    val count = iter.getVarInt
    val bhs = (for { _ <- 0 until count } yield {
      val tpe = iter.getInt
      val hash = iter.getHash
      InvEntry(tpe, hash)
    }) toList

    (GetTxData(bhs.filter(_.tpe == 1).map(_.hash)), GetBlockData(bhs.filter(_.tpe == 2).map(_.hash)))
  }
}
case class GetTxData(hashes: List[Hash]) extends GetData(1, hashes)
case class GetBlockData(hashes: List[Hash]) extends GetData(2, hashes)

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
    val txs = Array.range(0, bh.txCount).map { _ => Tx.parse(iter) }
    Block(bh, txs, bs)
  }
}

case class Tx(hash: Hash, version: Int, txIns: Array[TxIn], txOuts: Array[TxOut], lockTime: Int) extends BitcoinMessage {
  val command = "tx"
  def toByteString() = {
    val bb = new ByteStringBuilder
    bb.putInt(version)
    bb.putVarInt(txIns.length)
    for { txIn <- txIns } {
      bb.append(txIn.toByteString())
    }
    bb.putVarInt(txOuts.length)
    for { txOut <- txOuts } {
      bb.append(txOut.toByteString())
    }
    bb.putInt(lockTime)
    bb.result()
  }
  override def toString() = s"Tx(${hashToString(hash)}, ${txIns.size} -> ${txOuts.size})"
}
object Tx {
  def parse(bi: ByteIterator) = {
    val mark = bi.len
    val biCopy = bi.clone()
    val version = bi.getInt
    val txInCount = bi.getVarInt
    val txIns = Array.range(0, txInCount) map { _ => TxIn.parse(bi) }
    val txOutCount = bi.getVarInt
    val txOuts = Array.range(0, txOutCount) map { _ => TxOut.parse(bi) }
    val lockTime = bi.getInt
    val txLen = mark-bi.len
    val txBytes = biCopy.slice(0, txLen).toArray
    val txHash = dsha(txBytes)
    Tx(txHash, version, txIns, txOuts, lockTime)
  }
}

case class TxIn(prevOutPoint: OutPoint, sigScript: Script, sequence: Int) extends BitcoinMessage {
  val command = "txin"
  def toByteString() = {
    val bb = new ByteStringBuilder
    bb.append(prevOutPoint.toByteString())
    bb.putScript(sigScript)
    bb.putInt(sequence)
    bb.result()
  }
}
object TxIn {
  def parse(bi: ByteIterator): TxIn = {
    val outpoint = OutPoint.parse(bi)
    val sigScript = bi.getScript
    val sequence = bi.getInt
    TxIn(outpoint, sigScript, sequence)
  }
}

case class TxOut(value: Long, script: Script) extends BitcoinMessage {
  val command = "txout"
  def toByteString() = {
    val bb = new ByteStringBuilder
    bb.putLong(value)
    bb.putScript(script)
    bb.result()
  }
}
object TxOut {
  def parse(bi: ByteIterator): TxOut = {
    val value = bi.getLong
    val script = bi.getScript
    TxOut(value, script)
  }
}

case class OutPoint(hash: Hash, index: Int) extends BitcoinMessage {
  val command = "outpoint"
  def toByteString() = {
    val bb = new ByteStringBuilder
    bb.putBytes(hash)
    bb.putInt(index)
    bb.result()
  }
  override def toString() = s"OutPoint(${hashToString(hash)}, $index)"
}
object OutPoint {
  def parse(bi: ByteIterator): OutPoint = {
    val hash = bi.getHash
    val index = bi.getInt
    OutPoint(hash, index)
  }
}

case class InvEntry(tpe: Int, hash: Hash) {
  override def toString() = s"Inv(${tpe} ${hashToString(hash)})"
}

case class BlockHeader(hash: Hash, version: Int, prevHash: Hash, merkleRoot: Hash, timestamp: Instant, bits: Int, nonce: Int, txCount: Int) extends BitcoinMessage {
  val command = "blockheader"
  val target = BlockHeader.getTarget(bits)
  val pow = BlockHeader.maxHash/target
  override def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    bb.putInt(version)
    bb.putBytes(prevHash)
    bb.putBytes(merkleRoot)
    bb.putInt(timestamp.getEpochSecond.toInt)
    bb.putInt(bits)
    bb.putInt(nonce)
    bb.result() // NB: txCount is not saved!
  }
  override def toString() = s"BlockHeader(${hashToString(hash)}, ${hashToString(prevHash)})"
}

object BlockHeader {
  val maxHash = BigInt(0).setBit(256)
  def parse(bi: ByteIterator, blockHash: Hash, readTxCount: Boolean): BlockHeader = {
    val version = bi.getInt
    val prevHash = bi.getHash
    val merkleRoot = bi.getHash
    val timestamp = Instant.ofEpochSecond(bi.getInt)
    val bits = bi.getInt
    val nonce = bi.getInt
    val txCount = if (readTxCount) bi.getVarInt else 0
    BlockHeader(blockHash, version, prevHash, merkleRoot, timestamp, bits, nonce, txCount)
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

case class Inv(invs: List[InvEntry]) extends BitcoinMessage {
  val command = "inv"
  def toByteString() = {
    val bb = new ByteStringBuilder
    bb.putVarInt(invs.length)
    for { inv <- invs } {
      bb.putInt(inv.tpe)
      bb.putBytes(inv.hash)
    }
    bb.result()
  }
}
object Inv {
  def parse(bs: ByteString) = {
    val bi = bs.iterator
    val count = bi.getVarInt
    Inv((for { _ <- 0 until count } yield {
      val tpe = bi.getInt
      val hash = bi.getHash
      InvEntry(tpe, hash)
    }).toList)
  }
}

case object GetAddr extends BitcoinMessage {
  val command = "getaddr"
  def toByteString() = ByteString.empty
}

case class Addr(addrs: List[AddrEntry]) extends BitcoinMessage {
  val command = "addr"
  def toByteString() = ???
}
case class AddrEntry(timestamp: Instant, addr: InetSocketAddress)
object Addr {
  def parse(bs: ByteString) = {
    val bi = bs.iterator
    val count = bi.getVarInt
    Addr((for { _ <- 0 until count } yield {
      val t = bi.getInt
      val timestamp = Instant.ofEpochSecond(t)
      bi.drop(8)
      val addrBytes: Array[Byte] = new Array(16)
      bi.getBytes(addrBytes)
      val addr = InetAddress.getByAddress(addrBytes)
      val port: Int = bi.getShort(ByteOrder.BIG_ENDIAN).toInt & 0xFFFF
      AddrEntry(timestamp, new InetSocketAddress(addr, port))
    }).toList)
  }
}
