---
layout: page
sha: 232c3ce9ac75103d4c0d299c0d166d6713020f13
---

# Relay Transactions

Comparatively to the rest, relaying unconfirmed transactions is not too difficult. Our peers advertise their inventory
by sending `Inv` messages. We already use them to detect new blocks. Now we also need to use the transaction inventory.

We keep a `TxPool` which is a hash map of transaction hashes to transaction. Initially the pool is empty. When we
receive an Inv message, we look at the pool and see if we already know about this message. If so, nothing else happens.
If it's a new transaction, we insert an entry pointing to None. This indicates that we know about the transaction
but we haven't got the transaction itself. Then we send a `GetData` message to request it.

Once we get the transaction, we use our `checkTx` function to make sure that it is valid and then send out a `InvEntry`
message to all our peer actors. They will buffer the InvEntry together for 1 second before sending out an `Inv`.
This way we won't send an Inv message with a single transaction in it.

Whenever we receive a new block, we clear the pool. The `TxPool` is not the same memory pool as Bitcoin Core. We are not
mining so we don't care about having all the transactions. Our rule is that if a transaction isn't confirmed in one block,
we ditch it.

Next: [Accepting incoming connections]({{site.baseurl}}/misc/server.html)
