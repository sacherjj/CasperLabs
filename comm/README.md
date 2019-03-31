# CasperLabs Communication Subsystem

Network related operations for CasperLabs. 

## Build from the source

The only up-front build-time requirements are the Java Development Toolkit (we've been using [OpenJDK version
1.8.0_151](http://openjdk.java.net/install/)) and [sbt](http://www.scala-sbt.org/download.html), both of which should be installed
according to your platform.

Simply run `sbt comm/compile` to compile the project, `sbt comm/test` to run all the tests suite.

## Exposed programming API for other modules

1. [TransportLayer](/src/main/scala/io/casperlabs/comm/transport/TransportLayer.scala) - node2node communication
2. [NodeDiscovery](/src/main/scala/io/casperlabs/comm/discovery/NodeDiscovery.scala)  - node discovery within p2p network
3. [GossipService](/src/main/scala/io/casperlabs/comm/gossiping/GossipService.scala) - blocks gossiping
