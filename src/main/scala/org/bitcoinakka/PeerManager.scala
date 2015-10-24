package org.bitcoinakka

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import akka.io.Tcp.{Connected, ConnectionClosed}
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory

class Peer(connection: ActorRef) extends Actor with ActorLogging {
  def receive = {
    case Tcp.Received(data) =>
      log.info(s"Received ${Hex.encodeHexString(data.toArray)}")

    case _: ConnectionClosed =>
      log.info("Peer disconnected")
      context stop self
  }
}

class PeerManager extends Actor with ActorLogging {
  import PeerManager._
  import context.system

  def receive = {
    case ConnectToPeer(peerAddress) =>
      IO(Tcp) ! Tcp.Connect(peerAddress)

    case Connected(remote, local) =>
      log.info(s"Connected to ${remote}")
      val connection = sender
      val peer = context.actorOf(Props(new Peer(connection)))
      connection ! Tcp.Register(peer)
  }
}

object PeerManager extends App {
  case class ConnectToPeer(peerAddress: InetSocketAddress)

  val log = LoggerFactory.getLogger(getClass)
  implicit val system = ActorSystem()

  log.info("Hello, welcome to Bitcoin-akka")

  val peerManager = system.actorOf(Props(new PeerManager), "peermanager")
  peerManager ! PeerManager.ConnectToPeer(new InetSocketAddress("localhost", 9333))
}
