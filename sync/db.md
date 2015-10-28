---
layout: page
sha: 65cc5ee0c0b81cc921b5c6c7c2b5b4d6256fac2e
---

# Save/Load Block Headers

This commit is mainly about the impementation of `SyncPersist` as `SyncPersistDb`. The database schema is quite simple.
There is a table that has a sequence number as primary key and it stores the header in binary form as a column. In
addition, we have the block height and the cumulative proof of work, i.e. the sum of proof of work since the genesis
block.

The table could have used the block hash as its primary key but hashes make very poor keys because they behave
like uniformly random values. As we insert new rows, the table would get reorganized to stay ordered by hash.
Instead, we have a unique index from hash to row id.

The block height is a valuable piece of information that determines where we store the block on disk. The cumulative
proof of work allows us to trim the blockchain and only keep a short fragment of the full chain. Forks are compared
on their cumulative proof of work so by associating this value with the header we save ourselves the task of calculating
it over and over. The proof of work is a large integer value that doesn't fit in any of the integer types that MySQL has.
Therefore is is stored as a byte array.

The second table contains key value pairs but at this time it only has one entry: 'main'. The value is the hash of the
last block of our current blockchain. It is in string form so we can use SQL to quickly look at its value.

```sql
select * from properties where name='main';
```

In this code, we have the first usage of the ARM library (automatic resource management). Java is garbage collected and
memory resources are automatically cleaned up. However, non memory resources require manual cleanup. ARM helps doing
that by offering block patterns 

For example,

```scala
(for {
    s <- managed(connection.createStatement())
    rs <- managed(s.executeQuery("SELECT value FROM properties WHERE name='main'"))
  } yield rs) acquireAndGet { rs =>
    while (rs.next()) {
      val hashString = rs.getString(1)
      hash = hashFromString(hashString)
    }
  }
```

The statement and result sets are closed automatically.

One last thing that this code does is insert the genesis block data if the database is empty. Since we want to support
both the main net and the regtest net, we have two sets of blockchain data to choose from. Normally we use the main net
but if the application sees that the JVM variable "net" is set to "regtest", it will switch.
JVM variables can be set using the `-D` flag on the command line.

Next: [Simple implementation of Sync]({{site.baseurl}}/sync/simple.html)
