package org.bitcoinakka

import java.net.{InetAddress, InetSocketAddress}
import java.time.Instant
import java.time.temporal.ChronoUnit

import com.typesafe.config.Config

import scala.concurrent.duration._
import akka.actor._
import akka.io.{IO, Tcp}
import akka.io.Tcp.{Connect, CommandFailed, Connected, ConnectionClosed}
import org.slf4j.LoggerFactory
import BitcoinMessage._

object PeerState extends Enumeration {
  val Connecting, Connected, Ready = Value
}

class Peer(connection: ActorRef, local: InetSocketAddress, remote: InetSocketAddress) extends FSM[Peer.State, Peer.Data] with ActorLogging {
  import Peer._

  val messageHandler = context.actorOf(Props(new MessageHandlerActor(connection)))

  val myHeight = 0

  startWith(Initial, HandshakeData(None, false))

  setTimer("handshake", StateTimeout, timeout, false)
  messageHandler ! Version(BitcoinMessage.version, local, remote, 0L, "Bitcoin-akka", myHeight, 1.toByte)

  when(Initial) {
    case Event(v: Version, d: HandshakeData) =>
      messageHandler ! Verack
      checkHandshakeFinished(d copy (height = Some(v.height)))

    case Event(Verack, d: HandshakeData) =>
      checkHandshakeFinished(d copy (ackReceived = true))
  }

  when(Ready) {
    case Event(gh: GetHeaders, _) =>
      log.info(s"Peer received request for headers ${hashToString(gh.hashes.head)}")
      messageHandler ! gh
      setTimer("getheaders", StateTimeout, timeout, false)
      stay using ReplyToData(sender)

    case Event(gb: GetBlocks, _) =>
      val gbMessage = GetBlockData(gb.hsd.map(_.blockHeader.hash))
      messageHandler ! gbMessage
      log.info(s"Peer received request to download ${gb.hsd.map(hsd => hashToString(hsd.blockHeader.hash))}")
      setTimer("getblocks", StateTimeout, timeout, false)
      stay using ReplyToData(sender)

    case Event(headers: Headers, ReplyToData(s)) =>
      log.info(s"Headers received (${headers.blockHeaders.length})")
      cancelTimer("getheaders")
      s ! headers
      stay

    case Event(block: Block, ReplyToData(s)) =>
      log.info(s"Block received ${hashToString(block.header.hash)}")
      s ! block
      setTimer("getblocks", StateTimeout, timeout, false)
      stay

    case Event(GetBlocksFinished, _) =>
      cancelTimer("getblocks")
      stay
  }

  whenUnhandled {
    case Event(bm: BitcoinMessage, _) =>
      log.info(s"Received ${bm}")
      stay

    case Event(_: ConnectionClosed, _) =>
      log.info("Peer disconnected")
      context stop self
      stay

    case Event(StateTimeout, _) =>
      log.info("Peer timeout")
      context stop self
      stay
  }

  onTransition {
    case Initial -> Ready =>
      log.info("Handshake done")
      cancelTimer("handshake")
      context.parent ! Handshaked(nextStateData.asInstanceOf[HandshakeData].height.get)
  }

  initialize()

  private def checkHandshakeFinished(d: HandshakeData) = d match {
    case HandshakeData(Some(height), true) =>
      goto(Ready)
    case _ =>
      stay using d
  }
}

object Peer {
  val timeout = 5.second

  case class GetBlocks(hsd: List[HeaderSyncData])
  case class Handshaked(height: Int)
  case object GetBlocksFinished

  trait State
  object Initial extends State
  object Ready extends State

  trait Data
  case class HandshakeData(height: Option[Int], ackReceived: Boolean) extends Data
  case class ReplyToData(replyTo: ActorRef) extends Data
}

case class PeerAddress(timestamp: Instant, address: InetSocketAddress, used: Boolean)

class PeerManager(settings: AppSettingsImpl) extends Actor with ActorLogging {
  import PeerManager._
  import Peer._
  import context.system

  var peerId = 0
  var peersAvailable = Map.empty[String, PeerAddress]
  var peerStates = Map.empty[Int, PeerState.Value]
  var peersConnecting = Map.empty[String, Int]
  var peersConnected = Map.empty[ActorRef, Int]

  def receive = {
    case ConnectToPeer(peerAddress) =>
      IO(Tcp) ! Tcp.Connect(peerAddress)

    case AddPeer(peerAddress) =>
      val p = peersAvailable.getOrElse(peerAddress.address.getHostString, peerAddress)
      peersAvailable = peersAvailable.updated(peerAddress.address.getHostString, p copy (timestamp = peerAddress.timestamp))

    case Start =>
      log.info("Starting peer manager")
      tryConnect()

    case CommandFailed(c: Connect) =>
      log.info(s"Connection to ${c.remoteAddress} failed")
      val peerHostString = c.remoteAddress.getHostString
      val peerId = peersConnecting(peerHostString)
      peersConnecting -= peerHostString
      peerStates -= peerId
      tryConnect()

    case Connected(remote, local) =>
      log.info(s"Connected to ${remote}")
      val connection = sender
      val peerHostString = remote.getHostString
      val peerId = peersConnecting(peerHostString)
      val peer = context.actorOf(Props(new Peer(connection, local, remote)))
      context watch peer
      peersConnecting -= peerHostString
      peersConnected += peer -> peerId
      peerStates = peerStates.updated(peerId, PeerState.Connected)

    case Handshaked(height) =>
      val peer = sender
      val peerId = peersConnected(peer)
      peerStates = peerStates.updated(peerId, PeerState.Ready)
      log.info(s"Handshake from ${peer}")

    case Terminated(peer) =>
      val peerId = peersConnected(peer)
      peersConnected -= peer
      peerStates -= peerId
      tryConnect()
  }

  private def peerCount(state: PeerState.Value) = peerStates.values.count(_ == state)
  private def tryConnect() = {
    val cutoff = Instant.now.minus(3, ChronoUnit.HOURS)
    peersAvailable = peersAvailable.filter { case (_, v) => v.timestamp.isAfter(cutoff) }

    val targetConnectCount = settings.targetConnectCount
    val c = peerStates.size
    log.info(s"Peers available = ${peersAvailable.values.filter(!_.used).size}")
    log.info(s"Peers count = ${c} (connecting = ${peerCount(PeerState.Connecting)}, not handshaked = ${peerCount(PeerState.Connected)})")
    if (c < targetConnectCount) {
      val ps = peersAvailable.values.filter(p => !p.used).toList.take(targetConnectCount-c)
      for { p <- ps } {
        peersAvailable = peersAvailable.updated(p.address.getHostString, p copy (used = true))
        peersConnecting += p.address.getHostString -> peerId
        peerStates += peerId -> PeerState.Connecting
        self ! ConnectToPeer(p.address)
        peerId += 1
      }
    }
  }
}

object PeerManager extends App {
  case class ConnectToPeer(peerAddress: InetSocketAddress)
  case class AddPeer(address: PeerAddress)
  case object Start

  val log = LoggerFactory.getLogger(getClass)
  implicit val system = ActorSystem()
  implicit val settings = AppSettings(system)

  log.info("Hello, welcome to Bitcoin-akka")

  val peerManager = system.actorOf(Props(new PeerManager(settings)), "peermanager")

  loadFromDNSSeed() foreach (peerManager ! AddPeer(_))
  peerManager ! Start

  private def loadFromDNSSeed(): Array[PeerAddress] = {
    for { address <- InetAddress.getAllByName("seed.bitnodes.io") } yield {
      val a = InetAddress.getByAddress(address.getAddress)
      val peerAddress = new InetSocketAddress(a, 8333)
      PeerAddress(Instant.now, peerAddress, false)
    }
  }
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

class AppSettingsImpl(config: Config) extends Extension {
  val targetConnectCount = config.getInt("targetConnectCount")
}
object AppSettings extends ExtensionId[AppSettingsImpl] with ExtensionIdProvider {
  override def lookup = AppSettings
  override def createExtension(system: ExtendedActorSystem) = new AppSettingsImpl(system.settings.config)
}
