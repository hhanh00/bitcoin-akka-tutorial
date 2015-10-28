---
layout: page
sha: 2bd5b0d83980683b3758c2b1c5b48bb0adee8b8d
---

# Simple Sync

This commit introduces the overall flow of the sync, basically it is the main logic with specific sub functions stubbed out.
However, it is probably the most interesting part since it establishes the high level workflow.

```scala
def synchronize(provider: SyncDataProvider): Future[Boolean] = {
  val f = for {
    headers <- provider.getHeaders(blockchain.chainRev.take(10).map(_.blockHeader.hash))
    headersSyncData = attachHeaders(headers)
    _ <- Future.successful(()) if !headersSyncData.isEmpty
    _ <- provider.downloadBlocks(headersSyncData.tail)
  } yield {
      updateMain(headersSyncData)
      true
    }

  f recover {
    case _: java.util.NoSuchElementException => false
  }
}
```

It uses the `for {}` syntax to compose futures. It is not a loop but a notation for chaining futures. Every line in the for clause
that has `<-` refers to a future on the right side. The variable on the left side is the value returned by the future. The result
of the for is a future itself. It is a typical construct in functional languages in contrast to imperative languages where "for"
has no return value. Here `f` is a `Future[Boolean]` and completes when the workflow in the "for" completes.

The sync workflow goes through the steps:

- get headers from the remote side by using the provider (async),
- find the block in our current blockchain where these headers branch or extends from,
- if it doesn't attach anywhere, the previous function returns an empty list
- if that list is empty, fail the future with the exception `NoSuchElementException`
- download the corresponding blocks (async)
- update the main chain and return true

If there was a failure of type NoSuchElementException, recover and return false.

The actions are not done in parallel but asynchronously. The for statement uses the rules that the Future class has for sequencing
actions: When a future fails, the rest of the "for" will not be executed. That makes sense since we can't download blocks if we
can't get the headers.

If we can go through the whole thing, we can update the main chain.

```scala
private def updateMain(chain: List[HeaderSyncData]) = {
  chain match {
    case anchor :: newChain =>
      val chainRev = newChain.reverse ++ blockchain.chainRev.drop(blockchain.currentTip.height-anchor.height)
      blockchain = blockchain copy (chainRev = chainRev.take(Consensus.difficultyAdjustmentInterval))
      saveBlockchain(newChain)
      setMainTip(blockchain.currentTip.blockHeader.hash)
      log.info(s"Height = ${blockchain.currentTip.height}, POW = ${blockchain.currentTip.pow}")
    case _ => assert(false)
  }
}
```

The new chain attaches at the anchor node of our current chain. We have to trim out the remainder of the current chain starting from the
anchor and replace it with the new chain. At the end we only keep the last 2016 blocks. We can be sure that we have the previous
block header when the difficulty changed.

Next: [Downloading data]({{site.baseurl}}/sync/downloader.html)
