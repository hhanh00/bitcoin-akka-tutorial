---
layout: page
sha: 390c2363656e515fde36894b4ce3696dc6bcf7ed
---

# Parse handshake messages

Only two messages are needed: `Version` and `Verack`. Our side sends a Version message and receives a Verack in return.
Likewise, when we receive a Version message we should reply with Verack. When both parties have their Verack, the handshake
is established.

[Version][1] has a few fields but it's basically "our address", "your address", "a user string" and a protocol version number.
We are not paying too much attention to the content of the Version message because our application does not have specific
behavior based on the peer version. We are more interested in forming a correct message so that the other side is satisfied.

In one of the very late commits, we will generate most of the boilerplate serialization code but at this point, they are 
manually written.

Let's move to something more interesting: [Integrate message parser]({{site.baseurl}}/handshake/integration.html)

[1]: https://en.bitcoin.it/wiki/Protocol_documentation#version
