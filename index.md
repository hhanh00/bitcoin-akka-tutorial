---
layout: page
---

# What is Bitcoin-Akka ?

Bitcoin-akka is a "nearly" full node alternate implementation of the Bitcoin Core client in Scala using the Akka framework. It is aimed to be readable and serve as an introduction to functional programming and peer-to-peer applications in a real environment.

It should play nice with the bitcoin network. It will not emit ill-formed messages or in general violate the peer-to-peer protocol. In addition, it helps the network by:

- keeping up with the blockchain
- verifying blocks and transactions
- relaying good data

But its main purpose is to have fun coding. If you are looking for a care-free client, this is not it. I recommend the core client `Bitcoin Core`. It has many additional features and is the reference implementation. In bitcoin-akka, we want to get by with the minimal amount of code to get something nice and functional but it will require some elbow grease.

At the end, you will have a better understanding of the inner workings of Bitcoin as a technology and hopefully have a good taste of the Reactive style of programming.

Finally, bitcoin-akka is a small project. Altogether, it has less than 2000 lines of code with nearly 1/4 of it straightforward serialization/deserialization of messages. Once you start reading it, it's practically over! Nevertheless, it passes the bitcoin core [regression tests](https://github.com/TheBlueMatt/test-scripts).

I organized this walkthrough as a tutorial, assuming that you have no prior experience in Scala. You should have some programming experience. For example, you know what's an IDE and you are comfortable with the command line. 

> As usual, the standard disclaimer applies here. I claim no responsibility if you lose money by
using this software. It is provided for educational purposes only.

Grab a chair, fire up your command prompt and let's get started.
