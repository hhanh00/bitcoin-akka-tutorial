package org.bitcoinakka

import java.net.{InetAddress, InetSocketAddress}
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException

import akka.event.LoggingReceive
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import akka.actor._
import akka.io.{IO, Tcp}
import akka.io.Tcp.{Connect, CommandFailed, Connected, ConnectionClosed}
import org.slf4j.LoggerFactory
import BitcoinMessage._

import scala.util.Success

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
    case Event(inv: Inv, _) =>
      context.parent ! inv
      stay

    case Event(addr: Addr, _) =>
      context.parent ! addr
      stay

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
      messageHandler ! GetAddr
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

class PeerManager(settings: AppSettingsImpl) extends Actor with Sync with SyncPersistDb with Stash with ActorLogging {
  import PeerManager._
  import Peer._
  import context.system
  implicit val timeout = Timeout(1.hour)
  implicit val ec = context.dispatcher
  implicit val appSettings = settings

  val connection = DriverManager.getConnection(settings.bitcoinDb)
  connection.setAutoCommit(false)

  blockchain = loadBlockchain()

  var peerId = 0
  var peersAvailable = Map.empty[String, PeerAddress]
  var peerStates = Map.empty[Int, PeerState.Value]
  var peersConnecting = Map.empty[String, Int]
  var peersConnected = Map.empty[ActorRef, Int]

  val downloadManager = new DownloadManager(context)

  def receive: Receive = ({
    case sfp @ SyncFromPeer(peer, hash) =>
      if (peersConnected.contains(peer) && getHeaderSync(hash).isEmpty) {
        context.become(syncingReceive(peer) orElse commonReceive, discardOld = false)
        synchronize(downloadManager.create(peer)).onComplete {
          case Success(true) =>
            self ! sfp
            self ! SyncCompleted
          case _ =>
            self ! SyncCompleted
        }
      }
  }: Receive) orElse commonReceive

  def syncingReceive(peer: ActorRef): Receive = {
    case _: SyncFromPeer => stash()
    case SyncCompleted =>
      unstashAll()
      context.unbecome()
  }

  val commonReceive: Receive = LoggingReceive {
    case inv: Inv =>
      val peer = sender
      for {
        hash <- invBlocks(inv) if getHeaderSync(hash).isEmpty
      } self ! SyncFromPeer(peer, hash)

    case addr: Addr =>
      addr.addrs.foreach(addr => self ! AddPeer(PeerAddress(addr.timestamp, addr.addr, false)))

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
      downloadManager.addPeer(peer)
      self ! SyncFromPeer(peer, zeroHash)

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

  private def invBlocks(inv: Inv): List[Hash] = inv.invs.filter(_.tpe == 2).map(_.hash)
  private def invTxs(inv: Inv): List[Hash] = inv.invs.filter(_.tpe == 1).map(_.hash)
}

object PeerManager extends App {
  case class ConnectToPeer(peerAddress: InetSocketAddress)
  case class AddPeer(address: PeerAddress)
  case object Start
  case class SyncFromPeer(peer: ActorRef, hash: Hash)
  case object SyncCompleted

  val log = LoggerFactory.getLogger(getClass)

  Class.forName("com.mysql.jdbc.Driver")

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
      case "headers" => Some(Headers.parse(mh.payload))
      case "block" => Some(Block.parse(mh.payload))
      case "inv" => Some(Inv.parse(mh.payload))
      case "addr" => Some(Addr.parse(mh.payload))
      case _ => None
    }
  }
}

class DownloadManager(context: ActorRefFactory)(implicit settings: AppSettingsImpl) {
  private val log = LoggerFactory.getLogger(getClass)
  import DownloadManager._
  import concurrent.ExecutionContext.Implicits.global

  def addPeer(peer: ActorRef) = {
    d ! AddPeer(peer)
  }

  def create(syncPeer: ActorRef) = new SyncDataProvider {
    override def getHeaders(locators: List[Hash]): Future[List[BlockHeader]] = internalGetHeaders(syncPeer)(locators)
    override def downloadBlocks(hashes: List[HeaderSyncData]): Future[Unit] = internalDownloadBlocks(hashes)
  }

  def internalGetHeaders(syncPeer: ActorRef)(locators: List[Hash]): Future[List[BlockHeader]] = {
    val p = Promise[Headers]()
    context.actorOf(Props(new Actor {
      context watch syncPeer
      syncPeer ! GetHeaders(locators, zeroHash.array)

      def receive: Receive = {
        case headers: Headers =>
          context unwatch syncPeer
          context stop self
          p.success(headers)
        case Terminated(_) =>
          p.failure(new TimeoutException())
      }
    }))

    p.future.map(headers => headers.blockHeaders)
  }

  def internalDownloadBlocks(hashes: List[HeaderSyncData]): Future[Unit] = {
    val p = Promise[Unit]()
    d ! GetBlocks(hashes, p)
    p.future
  }

  case class IdleState(peers: Set[ActorRef])
  type IdleStateFunc = IdleState => IdleState
  def addIdle(peer: ActorRef): IdleStateFunc = { s => s copy (peers = s.peers + peer) }
  def removeIdle(peer: ActorRef): IdleStateFunc = { s => s copy (peers = s.peers - peer) }

  case class DownloaderState(p: Promise[Unit], tasks: List[Task], hashToHSD: Map[WHash, HeaderSyncData], workerToHashes: Map[ActorRef, Set[WHash]]) {
    def toStringLong() = s"(${tasks.map(t => t.map(h => hashToString(h.blockHeader.hash)))}, ${workerToHashes.map { case (a, hs) => a -> hs.map(h => hashToString(h.array))}})"
    override def toString() = s"${tasks.size}, ${workerToHashes.map { case (a, hs) => a -> hs.size}}"
  }
  type DownloaderStateFunc = DownloaderState => DownloaderState
  def add(peer: ActorRef): DownloaderStateFunc = { s => s copy (workerToHashes = s.workerToHashes.updated(peer, Set.empty)) }
  def checkCompleted(self: ActorRef): DownloaderStateFunc = { s =>
    if (s.tasks.isEmpty && s.workerToHashes.forall(_._2.isEmpty))
      self ! DownloadFinished
    s
  }
  def assignTask(self: ActorRef): DownloaderStateFunc = { s =>
    (for {
      task <- s.tasks.headOption
      idleWorker <- s.workerToHashes.find(_._2.isEmpty).map(_._1)
    } yield {
        idleWorker.tell(Peer.GetBlocks(task), self)
        s copy (tasks = s.tasks.tail, workerToHashes = s.workerToHashes.updated(idleWorker, task.map(hsd => hsd.blockHeader.hash.wrapped).toSet))
      }) getOrElse s
  }
  def receiveBlock(peer: ActorRef, block: Block): DownloaderStateFunc = { s =>
    val wHash = block.header.hash.wrapped
    (for {
      hashes <- s.workerToHashes.get(peer)
    } yield {
        val height = s.hashToHSD(wHash).height
        // TODO: Save block -- blockStore.saveBlock(block, height)
        s copy (workerToHashes = s.workerToHashes.updated(peer, hashes - wHash))
      }) getOrElse s
  }
  def completeWorker(peer: ActorRef, self: ActorRef): DownloaderStateFunc = { s =>
    (for {
      hashes <- s.workerToHashes.get(peer)
    } yield {
        if (hashes.isEmpty) {
          peer ! Peer.GetBlocksFinished
          assignTask(self)(s)
        }
        else s
      }) getOrElse s
  }
  def failWorker(peer: ActorRef): DownloaderStateFunc = { s =>
    (for {
      failedHashes <- s.workerToHashes.get(peer)
    } yield {
        val task = failedHashes.map(h => s.hashToHSD(h)).toList
        s copy (tasks = if (task.isEmpty) s.tasks else task :: s.tasks, workerToHashes = s.workerToHashes - peer)
      }) getOrElse s
  }

  val d = context.actorOf(Props(new Actor {
    def receive = idleReceive(IdleState(Set.empty))
    def idleReceive(state: IdleState): Receive = {
      case GetBlocks(hsd, p) =>
        val tasks = hsd.grouped(settings.batchSize).toList
        val hashToHSD = hsd.map(h => h.blockHeader.hash.wrapped -> h).toMap

        def internalReceive(state: DownloaderState): Receive = {
          log.info(s"${state}")
          checkCompleted(self)(state)

          {
            case AddPeer(peer) =>
              log.info(s"Adding peer ${peer}")
              context watch peer
              context become internalReceive((add(peer) andThen assignTask(self))(state))

            case block: Block =>
              val peer = sender
              context become internalReceive((receiveBlock(peer, block) andThen completeWorker(peer, self))(state))

            case Terminated(peer) =>
              log.info(s"Peer ${peer} terminated")
              context become internalReceive((failWorker(peer) andThen assignTask(self))(state))

            case DownloadFinished =>
              state.p.success(())
              context become idleReceive(IdleState(state.workerToHashes.keys.toSet))
          }
        }
        val downloadState = DownloaderState(p, tasks, hashToHSD, state.peers.map(p => p -> Set.empty[WHash]).toMap)
        val assignTaskToAll = state.peers.map(p => assignTask(self)).foldLeft(identity[DownloaderState] _)(_ andThen _)
        context become internalReceive(assignTaskToAll(downloadState))

      case AddPeer(peer) =>
        log.info(s"Adding peer ${peer}")
        context watch peer
        context become idleReceive(addIdle(peer)(state))

      case Terminated(peer) =>
        log.info(s"Peer ${peer} terminated")
        context become idleReceive(removeIdle(peer)(state))
    }
  }))
}
object DownloadManager {
  case class AddPeer(peer: ActorRef)
  case class GetBlocks(hashes: List[HeaderSyncData], p: Promise[Unit])
  case object DownloadFinished
  type Task = List[HeaderSyncData]
}

class AppSettingsImpl(config: Config) extends Extension {
  val targetConnectCount = config.getInt("targetConnectCount")
  val bitcoinDb = config.getString("bitcoinDb")
  val batchSize = config.getInt("batchSize")
}
object AppSettings extends ExtensionId[AppSettingsImpl] with ExtensionIdProvider {
  override def lookup = AppSettings
  override def createExtension(system: ExtendedActorSystem) = new AppSettingsImpl(system.settings.config)
}
