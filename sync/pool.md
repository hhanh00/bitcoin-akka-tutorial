---
layout: page
sha: 2fdc62b83837a199c77d7988134ce38a3926b91b
---

# Pool of Peers

It is essentially a connection pool. The Peer Manager picks node addresses from a set that it got from either a DNS query (seeds)
or from peer to peer discovery. Then it tries to connect and handshake. If it fails, the address is burned out and will not
be retried later. Only addresses that have a timestamp of less than 3h are considered.

First we need to read the configuration setting for the pool size. Akka already uses a configuration file and we will just
add new settings to it.

To extend it, we hook up at their application extension point.

```scala
class AppSettingsImpl(config: Config) extends Extension {
  val targetConnectCount = config.getInt("targetConnectCount")
}
object AppSettings extends ExtensionId[AppSettingsImpl] with ExtensionIdProvider {
  override def lookup = AppSettings
  override def createExtension(system: ExtendedActorSystem) = new AppSettingsImpl(system.settings.config)
}
```

We get the initial list of nodes from DNS. It's just a query to a hard coded name and the IPs are assumed to have
a node at their port 8333.

```scala
private def loadFromDNSSeed(): Array[PeerAddress] = {
  for { address <- InetAddress.getAllByName("seed.bitnodes.io") } yield {
    val a = InetAddress.getByAddress(address.getAddress)
    val peerAddress = new InetSocketAddress(a, 8333)
    PeerAddress(Instant.now, peerAddress, false)
  }
}
```

This mechanism replaces our previously hard coded "localhost".

Finally, we implement a pool of peers. In the pool, they are in one of the three possible states: 

```scala
object PeerState extends Enumeration {
  val Connecting, Connected, Ready = Value
}
```

- `Connecting` means that the peer manager has sent a "Connect" message to the IO system but hasn't received a reply yet,
- `Connected` means that the connection is established and the peer actor started but the the peer hasn't completed the
handshake,
- `Ready` means that the peer is ready to communicate.

Peers are tracked in four Maps (hash tables). 

- `peersAvailable` is the group of addresses that the peer manager can pick new nodes from,
- `peersConnecting` keeps track of the peers in the connecting state. They have a ID but no ActorRef, so they are keyed by
the host string,
- `peersConnected` are connected peers. They have an ActorRef and an ID,
- `peerStates` maps the ID to a state.

The main logic is in the `tryConnect` function which determines if the Peer Manager should attempt to connect to more nodes.
It checks if the current pool is undersized and if so pulls from the set of peersAvailable. It doesn't remove entries from
peersAvailable because some peer can send us the same address again and want to remember that an address has been used before.

In the next section, we prepare the ground for receiving headers by implementing the [persistence]({{site.baseurl}}/sync/db.html)
layer.