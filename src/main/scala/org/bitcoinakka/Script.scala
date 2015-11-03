package org.bitcoinakka

import BitcoinMessage._
import akka.util.ByteStringBuilder

import collection.mutable

class ScriptInterpreter {
  def verifyScript(scriptPubKey: Script, tx: Tx, index: Int, flags: Int): Int = ???

  def sigCheck(hash: Hash, pub: Array[Byte], sig: Array[Byte]): Boolean = ???
}
object ScriptInterpreter {
  type StackElement = Array[Byte]
  type ScriptStack = mutable.Stack[StackElement]
  val one = new Hash(32)
  one(0) = 1

  def sigHash(tx: Tx)(index: Int, subScript: Script, sigType: Int): Hash = {
    val anyoneCanPay = (sigType & 0x80) != 0
    val sigHashType = sigType match {
      case 2|3 => sigType
      case _ => 1
    }

    if (sigHashType == 3 && index >= tx.txOuts.length)
      one
    else {
      val bb = new ByteStringBuilder()
      bb.putInt(tx.version)
      if (anyoneCanPay) {
        bb.putVarInt(1)
        val txIn = TxIn(tx.txIns(index).prevOutPoint, subScript, tx.txIns(index).sequence)
        bb.append(txIn.toByteString())
      }
      else {
        bb.putVarInt(tx.txIns.length)
        for {(txIn, i) <- tx.txIns.zipWithIndex} {
          val txIn2 = TxIn(txIn.prevOutPoint,
            if (i == index) subScript else Array.empty[Byte],
            if (sigHashType != 1 && i != index) 0 else txIn.sequence)
          bb.append(txIn2.toByteString())
        }
      }

      sigHashType match {
        case 1 =>
          bb.putVarInt(tx.txOuts.length)
          tx.txOuts.foreach(txOut => bb.append(txOut.toByteString()))
        case 2 =>
          bb.putVarInt(0)
        case 3 =>
          bb.putVarInt(index+1)
          for (i <- 0 to index) {
            if (i < index)
              bb.append(TxOut(-1L, Array.empty).toByteString())
            else
              bb.append(tx.txOuts(index).toByteString())
          }
      }
      bb.putInt(tx.lockTime)
      bb.putInt(sigType)

      dsha(bb.result().toArray)
    }
  }

  def push(stack: ScriptStack, v: StackElement) = stack.push(v)
  def pop(stack: ScriptStack): StackElement = stack.pop()
  def peek(stack: ScriptStack): StackElement = stack.head

  def bigIntToStackElement(i: BigInt): Array[Byte] = {
    val absBI = i.abs
    val bi = absBI.toByteArray // big endian
    val bi2 = if (i < 0 && (bi(0) & 0x80) != 0)
      0.toByte +: bi
    else bi
    if (i < 0)
      bi2(0) = (bi2(0) | 0x80).toByte
    bi2.reverse
  }

  def intToStackElement(i: Int) = bigIntToStackElement(i)
  def longToStackElement(i: Long) = bigIntToStackElement(i)

  def bytesToInt(e: StackElement): BigInt = {
    val bi = e.reverse
    val isPositive = (bi(0) & 0x80) == 0
    bi(0) = (bi(0) & 0x7F).toByte
    val absBI = BigInt(bi)
    if (isPositive) absBI else -absBI
  }
}
