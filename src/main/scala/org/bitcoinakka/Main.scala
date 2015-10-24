package org.bitcoinakka

import java.nio.ByteOrder

import akka.util.ByteString

import scalaz.EphemeralStream

case class MessageHeader(command: String, length: Int, checksum: Array[Byte], payload: ByteString) {
  val totalLength = 24 + length
}

class MessageHandler {
  implicit val bo = ByteOrder.LITTLE_ENDIAN
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
