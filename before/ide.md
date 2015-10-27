---
layout: page
sha: b235665fcacce26a0b6a7db0622569ada8a1b147
---

# IDE

An IDE is optional, one can develop everything with a text editor and the command line. However, a good IDE can make
a significant difference in the day to day experience. With Scala, there are two main options: IntelliJ IDEA and
Eclipse. Of the two, I think IntelliJ is more powerful but it consumes more resources. Neither of them are perfect
and they both have issues when it comes to debug Scala code. In the following, I'll assume you use IntelliJ.

Clone the project and checkout the commit "Project build file and dependencies".

The commit is at the bottom of the page. To check out a given commit, enter

`$ git checkout sha256`

For example, `git checkout b235665fcacce26a0b6a7db0622569ada8a1b147`

![Main]({{site.baseurl}}/images/intellij-main.png)

Choose "Open", then select the directory where you clone bitcoin akka at the first revision.
Check "Create directories ..."

![Open Project]({{site.baseurl}}/images/import-project.png)

After a while, your screen will look like this.

![Initial Project]({{site.baseurl}}/images/initial-project.png)

The only files that we have so far are the application configuration and the log4j2 configuration.

The application config redirects debug outputs from Akka's logger to SLF4J which in terms will use log4j2.
In the log4j2 config, we write INFO level messages to the console and to the file `console.log`.

The build file `build.sbt` declares the project dependencies among other things. Bitcoin-akka uses 

- [akka][1]: Actor library, used for IO and concurrency
- [apache commons][2]: Some utility functions
- [apache codec][3]: Convert hex to strings
- [scalaz][4]: Functional library
- [scala-arm][5]: Resource management library
- [mysql jdbc driver][6]
- [leveldb jni][7]: Native bridge to leveldb
- [sl4j][8], [log4j][9]: logging
- [scala-test][10]: unit tests
- [bitcoinj][11]: generate the test cases
- [pickling][12]: serializer used to read/write test cases

Now Build the projects. It should have 0 errors and 0 warnings. There is nothing yet. Let's add some code.

For the first milestone, we want to be able to connect to a peer and shake hands. At the end, our node
will be idle.

Next: [Message header parser]({{site.baseurl}}/handshake/header.html)

[1]: http://akka.io/
[2]: https://commons.apache.org/
[3]: https://commons.apache.org/proper/commons-codec/
[4]: https://github.com/scalaz/scalaz
[5]: http://jsuereth.com/scala-arm/usage.html
[6]: http://dev.mysql.com/downloads/connector/j/
[7]: https://github.com/fusesource/leveldbjni
[8]: http://www.slf4j.org/
[9]: http://logging.apache.org/log4j/2.x/
[10]: http://www.scalatest.org/
[11]: https://github.com/bitcoinj/bitcoinj
[12]: https://github.com/scala/pickling
