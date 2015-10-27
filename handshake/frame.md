---
layout: page
sha: 2b131de1436561f064b094ac54509f444b822d36
---

# Parse multiple messages

Now that we have a function that extracts a message from a byte string, we can get all the messages from an 
incoming buffer by repeatedly calling that function. There is a standard function called `unfold` that does this.

The idea of unfold is to do the logically reverse of a fold. A fold starts with a container that can be traversed,
a list for example, and a value. It takes a "folding" function that combines two values and returns one. Fold
applies the folding function using the initial value and the first element of the container, then the 2nd, then the
3rd, etc. until the container is exhausted. 

Many algorithms can be modelled by a fold with a specific folding function. For example calculating the sum of the 
elements of a list is computed by folding with `+`.

Unfold does the reverse. It takes a value and applies an "unfolding" function that produces a pair of values.
It puts the 1st one in a list and reapply the unfolding function to the second one. It keeps doing it until
the unfolding function returns nothing.

We can get a list of messages by using "unfold" with "parseMessageHeader".

```scala
var buffer = ByteString.empty
def frame(data: ByteString): List[MessageHeader] = {
  buffer ++= data

  val messages = EphemeralStream.unfold(buffer)(parseMessageHeader).toList
  buffer = buffer.drop(messages.map(_.totalLength).sum)
  messages
}
```

After we have the list of message headers, we calculate their total length and drop as many bytes from the buffer.
This should be done because our buffer is immutable. We are not changing the value of buffer, we are making 
`buffer` refer to a different buffer.

Next: Introduce the base class for all the [messages]({{site.baseurl}}/handshake/message.html)
