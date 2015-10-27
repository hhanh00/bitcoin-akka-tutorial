---
layout: page
---

# Setup

`Bitcoin-akka` has been tested on Linux, Mac and Windows. Thanks to the portability of the JVM, there were no change 
to the code to make that possible. However, it depends on a few other libraries and products that may need some work
to get running.

- A database where it stores the block headers and the current chain state. It uses MySQL though the SQL isn't 
particulary advanced, moving to another database would require some modification,
- `libbitcoinconsensus` comes from Bitcoin Core. As far as I know the build process doesn't target the library alone
but it is possible to manually stop the build once libbitcoinconsensus is built. Fortunately, it is the first binary,
- Bitcoin-akka has a JNI wrapper for the previous library. It is a trivial piece of code but like every JNI code,
it must be built independently.

This tutorial assumes that you use a Linux machine. I work under Windows - the only significant difference is in the
build of the consensus library. On Linux, it's quite easy. On Windows, it's a real challenge. If you prefer to grab
a binary for it, I have [one for Windows 64][1]. 

# Build

## JNI Wrapper

- The code is under `src/main/c`
- Make sure you have the environment variable JDK_HOME pointing to your JDK directory. For example `/usr/lib/jvm/java-8-oracle`
- In the following, lines that begin with `$` indicate a command. The `$` shouldn't be typed in.
- Compile: `$ libtool --mode=compile gcc -I $JDK_HOME/include/ -I $JDK_HOME/include/linux/ -I include -c -o consensus-jni.o consensus-jni.c`
- Link: `$ libtool --mode=link gcc -o libconsensus-jni.la consensus-jni.lo -lbitcoinconsensus -rpath /usr/local/lib`
- Install: `$ sudo cp -av .libs/libconsensus-jni.so* /usr/local/lib`
- Update ldconfig: `$ sudo ldconfig`

[1]: https://github.com/hhanh00/bitcoin-akka/blob/master/src/main/c/lib/libbitcoinconsensus-0.dll

## Bitcoin-akka

- Install `sbt` [build tool][2]
- Build: `$ sbt assembly` (alternatively `$ sbt universal:packageBin` for a zip)
The binary is in `target/scala-2.11`

## Bitcoin

Full instructions are on the Bitcoin core project pages. Here's just a summary:

- Install C++ build environment: `$ sudo apt-get install autoconf libtool g++`
- Install boost libraries: `$ sudo apt-get install libboost-system1.54-dev libboost-filesystem1.54-dev libboost-program-options1.54-dev libboost-thread1.54-dev libboost-chrono1.54-dev libboost-test1.54-dev`
- Install openssh/libevent: `$ sudo apt-get install libssl-dev libevent-dev`
- Configure: `$ ./autogen.sh` then `$ ./configure --disable-wallet --with-boost-libdir=/usr/lib/x86_64-linux-gnu`
- Build: `$ make`
- Install: `$ sudo make install`

# Configuration

Sample config files are in the `src/main/resources` directory. You can either edit them and build, in this case they are embedded in the 
binary, or copy them to the build output location. In the first case, later you can run bitcoin-akka with

`$ java -Djava.library.path=/usr/local/lib -jar bitcoin-akka-tutorial-assembly-0.1.0-SNAPSHOT.jar`. 

In the second case, you need to add the directory where your config files are located to the classpath.

`$ java -Djava.library.path=/usr/local/lib -cp ".:bitcoin-akka-tutorial-assembly-0.1.0-SNAPSHOT.jar" org.bitcoinakka.PeerManager`

With the second way, you can update the configuration without rebuilding.

The SQL script creates a `bitcoindb_tutorial` database. If you are ok with this name, then there is nothing to change. Run:

`$ mysql -u root -p < src/main/sql/schema.sql`

The user name and password are also part of the configuration file so if you may have to update them there too.

Now at the very least, you need to change the `blockBaseDir` and `baseDir` settings to point to a directory where your account has access.
It's ok to have them be the same location.

# First time run

Bitcoin-akka should start and start downloading the first 2000 blocks. Then it will stop. 

> WHAT???

Bitcoin-akka enforces the latest rules of the protocol. Rules that were not in effect at the beginning. It rejected the first block
it got.

> WHAT??? What kind of lame excuse is that?

Well, if it had the rules that triggered the switch to the newer rules it would add a big chunk of code that is mostly boring and useless
now. Besides, there are much better ways to get the blockchain than that.

> OK. How?

Download a torrent that has a recent blockchain file or get it from the web or better yet find someone who has it and is willing to make
a copy for you. The blockchain is reaching 50 GB - unless you have a uber web connection - downloading it will take you days.

Next: [IDE]({{site.baseurl}}/before/ide.html)

TODO: Add link towards Import/Export section when it's available.
