---
layout: page
sha: 220bd0cc816a8743195c44725f2bd09da55d0cef
---

# Bugs

For the final commit, we are wrapping things up with a few small bug fixes.

[Bitnodes.io][1] refuses to detect our node and Bitcoin core shows strange data on the `getpeerinfo` report. It turns out that we should 

- have a protocol version number of at least 70001, 
- have // in our user agent name,
- and there were issues with sending the network address. The port should be sent in big endian and sometimes the java library gives us a IPv6 address instead of a IPv4.

We were also not sending our current height in the version message.

[1]: https://bitnodes.21.co/