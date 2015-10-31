---
layout: page
sha: 7c37548d6b64a02e5dfb684e88a2dcfbd232f134
---

# Remove Dependency on MySQL

Until this point, we were using MySQL because it has a management console and we could quickly experiment with the blockchain
table. With SQL we can run queries that tells what was our best chain, whether we had forks, how many blocks we had, etc.
However, it introduces a rather heavy dependency for a packaged product that benefits from being small.

In this commit, the dependency on MySQL is removed and we store the blockchain headers in a LevelDB database.
Because we have an interface that abstracts access to the headers table, switching to LevelDB is an isolated change.

The only sizeable modification appears in `SyncPersistDb`. As a matter of fact, the result is actually shorter than 
before. This isn't surprising since we are using MySQL as a key/value store. 

We map the empty key to the hash of the tip, the rest is a hash to serialized `HeaderSyncData`.

```scala
def setMainTip(newTip: Hash) = db.put(Array.empty, newTip)
```

The serialization macro can be applied to the `HeaderSyncData` to produce the reader/writer. The new version is simply

```scala
def saveBlockchain(chain: List[HeaderSyncData]) = {
  for { h <- chain } {
    val k = h.blockHeader.hash
    val v = h.toByteString().toArray
    db.put(k, v)
  }
}
```
