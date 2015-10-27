---
layout: page
sha: d9591a9e820afabb2b39114a6ca147aeb41f8a16
---

# Messages used during Sync

Let's get rid of the low-level protocol stuff first. There are a few of them as shown in the following diagram.

![Sync messages]({{site.baseurl}}/images/message-classes.png)

`GetHeaders` and `GetData` are the request messages. In response the remote node sends `Headers` and `Blocks`.
They simply all derive from the same base class. More interesting is the way they relate to each other.

![Sync messages]({{site.baseurl}}/images/blockchain-msg.png)

They are a few internal classes introduced here. Each block of the Blockchain contains one or many transactions.
Preceding the transactions, we have a block header. We can request only the block headers using the GetHeaders
command. Transactions have zero or many inputs and zero or many outputs. An input refers to another transaction
by position (a transaction hash and an output index).

The exchange of messages with the remote peer follows this sequence diagram.

![Sync messages]({{site.baseurl}}/images/blockchain-seq.png)

We get several headers at once in a reply to GetHeaders (up to 2000) but we get an individual block message as
replies to GetData.

The implementation of these classes isn't very special, the only point worth noting is that blocks and transactions
are identified by hashes which are not stored in them. Storing the hash is useless since it can be calculated.
The reader takes care of computing the hashes and storing it in the object. For blocks, it is also useful to extract
the header in its own class because it is the same object that is returned in the message.

