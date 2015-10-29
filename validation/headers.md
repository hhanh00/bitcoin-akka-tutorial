---
layout: page
sha: 4ed6e9a51a0efe555a0605a05a487d6507a07b0c
---

# Header Validation

Header validation comes in after the node receives the headers from a remote peer and has found the anchor block where
they branch or extend from. If they don't attach anywhere in our chain, they are simply rejected, the Sync finishes.

Assuming that they attach, we have now a list of headers - starting from the anchor and going forward. But there is no
reason to believe that. Without verification, a peer could give us a series of headers that are disconnected to each
other or even complete garbage. The goal of the header validation phase is to trim that list and only keep the "good"
headers.

We see that impact in the Sync workflow. We indeed call `validateHeaders` which returns the previous list split at the
first "bad" header. If all the headers are good as they should be, `badHeaders` is Nil.

```scala
_ <- isBetter(headersSyncData)
(goodHeaders, badHeaders) = validateHeaders(headersSyncData)
_ <- isBetter(goodHeaders)
_ <- provider.downloadBlocks(goodHeaders.tail)
```

Then we check the total proof of work of the good headers chain and proceed if and only if it is still higher than the
proof of work of our current chain.

Down the road, we will check the blocks and we will trim the list further. At every point, if we see that the current best
chain is worse than what we have, we stop as there is no chance of improvement.

The logic behind `validateHeaders` is actually quite simple. Given a list and a validation function, it goes through the list
and stops at the first time the validation function returns false. There is a function of the Scala library for that, called
`span`.

```scala
private def validateHeaders(headers: List[HeaderSyncData])(implicit blockchain: Blockchain): (List[HeaderSyncData], List[HeaderSyncData]) = {
  log.info(s"+ validateHeaders ${headers}")
  assert(!headers.isEmpty)
  val anchor = headers.head
  val r = headers.tail.spanM[HeaderCheckStateTrampoline](checkHeader).eval(HeaderCheckData(anchor, blockchain)).run
  val r2 = (anchor :: r._1, r._2)
  log.debug(s"-validateHeaders ${r2}")
  r2
}
```

However, `validateHeaders` uses a variant of `scan` called `scanM`. The M stands for Monad to show that it is similar to scan but 
works with a monad.

Very roughly speaking a [monad][1] is like a programmable sequence. The wikipedia entry isn't very clear and it's a concept specific
to functional programming languages, so it may not be something familiar to people coming from imperative languages.

[1]: https://en.wikipedia.org/wiki/Monad\_\(functional_programming\)

There are many types of monads and it's beyond the scope of this tutorial to describe them all. Here, we are using the State monad.
Basically, a monad defines a behavior put on top of another type. A monad has always an underlying type. We actually already saw one monad:
`Option`. Option is a parametrized type: Option isn't a type, `Option[Int]` is a type. When you have an instance and a function, Option
defines how we can `map` the instance with the function. If the value is `Some(x)`, it is mapped to `Some(f(x))`. Otherwise, if it's None
it's mapped to None. Option also has a flatten function that "pops" out one level. In other words, it turns a `Option[Option[T]]` into 
an `Option[T]`. If the value is None, it remains None. If it is `Some(None)`, flatten returns None. If it is `Some(Some(x))`, flatten returns
`Some(x)`. Since `Option` has these two methods (and they obey some consistency rules), it is a monad.

Monads are interesting because we can add - through them - a custom behavior when we write a series of expressions.

For example, when we did

```scala
val f = for {
  headers <- provider.getHeaders(blockchain.chainRev.take(10).map(_.blockHeader.hash))
  headersSyncData = attachHeaders(headers)
  _ <- Future.successful(()) if !headersSyncData.isEmpty
  _ <- isBetter(headersSyncData)
  ...
}
```

the compiler was using the `map` and `flatMap` functions of Option behind the covers. How? Let's take another monad as an example: List.

If you have a list of T and a function f, you can create a list of f(x). So list has the `map` function. What about the flatten? 
Flatten should transform a List of List into a regular List. The natural way to do so is to concatenate all the inner lists together and
indeed this works out correctly according to the consistency rules (got to take that on faith as we aren't going into details).

When we write

```scala
for {
	a <- listA
	b <- listB
} yield { a + b }
```

we expect to have nested loops. The compiler turns this syntax into (flatMap is map followed by flatten)

```scala
listA.flatMap(a => b.map(b => a + b))
```

If listA is (1, 2) and listB is (3, 4, 5), we get ((1+3), (1+4), (1+5), (2+3), (2+4), (2+5)), i.e. nested loops.

The same applies to Option. The compiler will generate the same sequence of `flatMap` and `map` but Option has its implementation for it.
The result is a fail fast behavior since as soon as we have None, Option won't proceed with the rest.

I know this explanation is a lot of hand waving and isn't particularly clear but I hope that it was enough to see the
usefulness of monads.

Okay, back to State. State is a monad that adds a state to a value. An instance of `State[S, T]` is a function that takes a S
and returns a pair (S, T), i.e. the new state and the value.

```scala
val r = headers.tail.spanM[HeaderCheckStateTrampoline](checkHeader).eval(HeaderCheckData(anchor, blockchain)).run
```

HeaderCheckStateTrampoline is our state monad. It stands for `State[HeaderCheckState, T]`. So values are functions of HeaderCheckState to
(HeaderCheckState, T). It's a bit weird because values are functions and we have to keep that in mind.

`checkHeader` for "span" would be a function from HeaderSyncData (the element type of "headers") to Boolean. Since we are working with
spanM, checkHeader is a function from HeaderSyncData to State[HeaderCheckState, Boolean]. In other words, a function of
HeaderCheckState to (HeaderCheckState, Boolean). We see now why this is interesting. HeaderCheckState can have the context we need
in order to verify that the header is valid and we can have this state updated. We just need to output the modified state and
we can have the library go through the headers list, roll the state from item to item and stop when the check fails.

We focus on writing the checkHeader function and the rest is standard. Every field of the header has to be checked in some way or another.

I skipped over the [Trampoline][2] part. It's necessary because otherwise we would be overflowing the stack. Every element of the
list introduces another level of nesting. We can have up to 2000 block headers in a single Headers message. It's too deep for the JVM
stack (which doesn't have [Tail Call Optimization][3]). So we have to transform nested calls into a [thunk][4]. This has nothing
to do with functionality but is only an artifact of the platform. I just mention these points in case you wonder where this
trampoline comes in play.

So what do we need to check?

```scala
case class HeaderCheckData(height: Int, prevHash: Hash, timestamps: Queue[Long], prevBits: Int, now2h: Long, prevTimestamp: Long, 
elapsedSinceAdjustment: Long)
```

We need:

- `height` because the difficulty is readjusted every 2016 blocks,
- `prevHash` because the prevHash field of the current header must be the same as the hash of the previous header,
- `timestamps` is a queue of the timestamps of the preceding 11 blocks. We need that to calculate the median because
a block should have a timestamp after that median time,
- `prevBits` is the difficulty of the previous block. If there are no difficulty readjustment, this header must have
the same difficulty,
- `now2h` is the timestamp 2 hours from now. We shouldn't accept blocks after that time,
- `prevTimestamp` because we need to update `elapsedSinceAdjustment`,
- `elapsedSinceAdjustment` is the time elapsed since the timestamp of the previous adjustment.

With this information, it is simple to update the new state after we check a header:

```scala
  def checkHeader(header: HeaderSyncData) = StateT[Trampoline, HeaderCheckData, Boolean] { checkData =>
    val height = checkData.height+1
    val blockHeader = header.blockHeader
    val median = checkData.timestamps.sorted.apply(5)
    val timestamp = blockHeader.timestamp.getEpochSecond

    var updatedCheckData = checkData copy (height = height, prevHash = blockHeader.hash,
      timestamps = checkData.timestamps.tail :+ timestamp, prevBits = blockHeader.bits,
      elapsedSinceAdjustment = checkData.elapsedSinceAdjustment+(timestamp-checkData.prevTimestamp),
      prevTimestamp = timestamp)

		...
  }
```

Of course `checkHeader` also should return true/false depending on the validity of the header.

```scala
    val prevHashMatch = checkData.prevHash.sameElements(header.blockHeader.prevHash)
    val timestampMatch = median < timestamp && timestamp < checkData.now2h
    val hashMatch = BigInt(1, blockHeader.hash.reverse) < blockHeader.target
    val bitsMatch = if (height >= Blockchain.firstDifficultyAdjustment && height % difficultyAdjustmentInterval == 0) {
      updatedCheckData = updatedCheckData copy (elapsedSinceAdjustment = 0L)
      blockHeader.bits == adjustedDifficulty(blockHeader, BlockHeader.getTarget(checkData.prevBits), checkData.elapsedSinceAdjustment)
    }
    else (blockHeader.bits == checkData.prevBits)

    val result = prevHashMatch && timestampMatch && hashMatch && versionMatch && bitsMatch
```

`adjustedDifficulty` is a helper function that computes the new difficulty based on the time spent since the last adjustment.
Bitcoin aims to have an average of 10 minute between blocks.

In the next section, we expect the content of the blocks and reject the ones that don't pass the 
[block validation]({{site.baseurl}}/validation/blocks.html) rules.

[2]: https://en.wikipedia.org/wiki/Trampoline\_\(computing\)
[3]: https://en.wikipedia.org/wiki/Tail\_call
[4]: https://en.wikipedia.org/wiki/Thunk
