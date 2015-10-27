---
layout: page
sha: f34decc2e035f3fd1277ef01df607ad6d297f220
---

# Sample connection code

The PeerManager is going to be the main entry point. At this stage, it is finally a runnable application.
Right click on PeerManager.scala in the project pane and choose "Run PeerManager".

The output will be

```
[INFO ] 2015-10-27 14:47:47.892 [default-akka.actor.default-dispatcher-3] [] Slf4jLogger - Slf4jLogger started
[INFO ] 2015-10-27 14:47:47.913 [main] [] PeerManager$ - Hello, welcome to Bitcoin-akka
```

A bit lackluster but we don't have anyone to connect to. If you have an installation of Bitcoin Core you can 
try connecting to it. At the end of this tutorial, we can also connect to another instance of Bitcoin-akka.

This code is our first real usage of Akka. Akka is essentially an [actor library][1]. In addition to the fundamental
features of the actor model, it has an API for TCP/UDP communication.

TCP connections follow the sequence diagram below.

![Connection sequence]({{site.baseurl}}/images/connection.png)

The initiator sends a "Connect" message to the IO subsystem passing the remote endpoint. The IO actor attempts to
connect. If it's successful, it creates a Connection actor. The Connection actor sends the "Connected"
message to the initiator. The later knows where the message comes from by looking at the sender. The initiator
creates a message handler and passes a reference to the connection actor. The handler has to register itself
to the connection actor (otherwise the connection actor does not know where to send the messages it receives).
At this point, both handler and connection actors can talk to each other.

If the connection closes from the remote, the handler receives "ConnectionClosed". If the handler wants to initiate
the closing, it sends "Tcp.Close"

In this pattern, the initiator steps aside once the connection is established. In our application, the PeerManager
plays the role of the initiator. 

Next section: [Peer]({{site.baseurl}}/handshake/peer.html)

[1]: https://en.wikipedia.org/wiki/Actor_model

