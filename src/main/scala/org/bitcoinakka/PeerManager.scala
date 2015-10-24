package org.bitcoinakka

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import akka.io.Tcp.{Connected, ConnectionClosed}
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory

class Peer(connection: ActorRef) extends FSM[Peer.State, Peer.Data] with ActorLogging {
  import Peer._

  val messageHandler = context.actorOf(Props(new MessageHandlerActor(connection)))
  startWith(Initial, NoData)

  when(Initial) {
    case Event(bm: BitcoinMessage, _) =>
      log.info(s"Received ${bm}")
      stay

    case Event(_: ConnectionClosed, _) =>
      log.info("Peer disconnected")
      context stop self
      stay
  }

  initialize()
}

object Peer {
  trait State
  object Initial extends State

  trait Data
  object NoData extends Data
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

class MessageHandlerActor(connection: ActorRef) extends Actor with MessageHandler with ActorLogging {
  connection ! Tcp.Register(self)
  def receive = {
    case Tcp.Received(data) => frame(data).flatMap(parse).foreach(context.parent ! _)
    case bm: BitcoinMessage => connection ! Tcp.Write(bm.toMessage())
    case other => context.parent ! other
  }

  private def parse(mh: MessageHeader): Option[BitcoinMessage] = {
    mh.command match {
      case "version" => Some(Version.parse(mh.payload))
      case "verack" => Some(Verack)
      case _ => None
    }
  }
}
