---
layout: page
sha: 284583d3e838c62ff0a728ddc832612732fd38c9
---

# Running the Regression Tests

I won't go into what the tests exactly do. Let's just say that they check many consensus rules and try out
a variety of forking scenarios.

Bitcoin-akka passes all of them except one: BIP-30 is commented out because it is now surperfluous now that BIP-34 is in effect.

To run them, you need to start with a blank database and make Bitcoin-akka use the reg test net with `-Dnet=regtest`.
Execute `BuildTestSuite` in your IDE. That will create the test data. It expires after a few hours because one of the
test is time dependent. 

Then run the test named "Sync trait" should "run regression tests" in `SyncSpec.scala`. It will succeed even if there
is a mismatch, so you should search for the string "MISMATCH" in the console output. There shouldn't be any occurence
of it.

The next step is optional and is about [auto generating]({{site.baseurl}}/misc/macro.html)
the serialization classes from `Message.scala`