# CasperLabs Node

CasperLabs Node is a module that gathers all other subprojects into final executable.

## 1. Building from source

```console
sudo sbt -Dsbt.log.noformat=true clean node/rpm:packageBin node/debian:packageBin node/universal:packageZipTarball
```
The build artifacts will be located in the `node/target/universal` directory if the build is successful.


### 1.2 Building Docker image

Run:
```console
sbt -Dsbt.log.noformat=true clean node/docker:publishLocal
```

Or you can run one of `make docker-build-all` or `make docker-build/node` as well.

To test if the image is available to use, simply run `docker images`, `casperlabs/node` should be on the list of available images.

```
$ docker images | grep casperlabs
casperlabs/node                               latest              b1af7024d9bf        6 minutes ago       131MB
```


## 2. Modes

The node module comes with an executable (jar, docker image, debian or fedora package), that can run in a few modes. Which mode is running depends on the flag you use.
To see the list of available flags you can run `./casperlabs-node --help`

### 2.1 The Node
By default when you execute the program, it will fire up a running node instance. That instance will become either a bootstrap node (see `--standalone` flag) or will try to connect to existing bootstrap.

Node will instantiate a peer-to-peer network. It will either connect to some already existing node in the network (called bootstrap node) or will create a new network (essentially acting as bootstrap node). __Note__ This release prints a great deal of diagnostic information.

An CasperLabs node is addressed by a "node address", which has the following form

```
casperlabs://<address-key>@<host-or-ip>:<tcp-port>
```

This version generates (non-cryptographically) random address keys of 128 bits, or 32 characters (UUIDs,
essentially). Future releases will generate full-length 256- or 512-bit node addresses, but for demonstration purposes,
128 bits is about the limit of manageability.

By default - when run without any flag - the communication system attempts to connect itself to the test CasperLabs network by bootstrapping from a known node in that network.

Using flags you can specify which bootstrapping node should be used or if the node should create its own network (become a bootstrap node).

#### 2.1.1 gRPC API

Node exposes its API via gRPC services, which are exposed on `grpc-port`. To see the list of all available services, RPC calls, possible requests and responses, please see [models/src/main/protobuf/CasperMessage.proto](https://github.com/CasperLabs/CasperLabs/blob/dev/models/src/main/protobuf/CasperMessage.proto)

#### 2.1.2 Data directory

Node needs to have read and write access to a folder called data directory. By default that folder is `$HOME/.casperlabs`. User can control that value by providing `--server-data-dir` flag.
Regardless of which path on the file system you choose for the data directory, please remember that node needs to have read and write access to that folder.

#### 2.1.2 Running the Node

Node runs as a server, and requires a specific network configuration.

##### 2.1.2.1 Running via Docker

An easy way to run CasperLabs Node is by using Docker. Use this pull command in Docker to get the current version of Node.

```docker pull casperlabs/node```

You can also [build a docker image yourself](#building-via-docker) and then run it.

```console
$ docker run -ti casperlabs/node run
15:01:48.557 [main] INFO  io.casperlabs.node.Main$ - CasperLabs node (3ec3baf422f0b8055df8d6dc0414664736a392c0)
15:01:48.564 [main] INFO  io.casperlabs.node.NodeEnvironment$ - Using data dir: /root/.casperlabs
15:01:48.577 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - No certificate found at path /root/.casperlabs/node.certificate.pem
15:01:48.578 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a X.509 certificate for the node
15:01:48.581 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a PEM secret key for the node
15:01:48.752 [main] INFO  io.casperlabs.comm.UPnP$ - trying to open ports using UPnP....
15:01:57.840 [main] INFO  io.casperlabs.comm.UPnP$ - INFO - No gateway devices found
15:01:57.841 [main] INFO  io.casperlabs.comm.UPnP$ - No need to open any port
15:01:57.843 [main] INFO  io.casperlabs.comm.WhoAmI$ - flag --host was not provided, guessing your external IP address
15:01:58.090 [main] INFO  io.casperlabs.comm.WhoAmI$ - guessed 78.144.212.177 from source: AmazonAWS service
15:01:58.135 [main] WARN  io.casperlabs.node.NodeRuntime - Can't delete file or directory /root/.casperlabs/tmp/comm: No such file
15:01:58.427 [main] INFO  i.c.c.util.comm.CasperPacketHandler$ - Starting in default mode
15:01:58.856 [main] WARN  i.casperlabs.casper.genesis.Genesis$ - Specified bonds file None does not exist. Falling back on generating random validators.
15:01:58.883 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 7a02431ef450b452d35157d0b4807eac1361d45fc02192be2de833b7c8b04b3e with bond 4
15:01:58.886 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 6a326171165344ebefaf5af3f21ce2be5791e357ee3f84300ce61773ba9a5ea5 with bond 2
15:01:58.889 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator daa206aca8e52192a441da12c1da00472bc4eafa925837480c0e4bdfa84cdb26 with bond 3
15:01:58.891 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 673c9928ee163cd31fa2f081743ac3c4c691b69597d68dcdd6a65db2bcbf4df8 with bond 1
15:01:58.893 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 4535b3d824e5efa32f85477e199e39594b8910079a362df864e733861dad3025 with bond 5
15:01:58.899 [main] WARN  i.c.casper.ValidatorIdentity$ - No private key detected, cannot create validator identification.
15:01:59.018 [main] INFO  io.casperlabs.node.NodeRuntime - Starting node that will bootstrap from casperlabs://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.109?protocol=40400&discovery=40404
15:01:59.182 [main] INFO  io.casperlabs.node.MetricsRuntime - No Influx configuration found
15:01:59.188 [main] INFO  io.casperlabs.node.MetricsRuntime - Reporting metrics to InfluxDB disabled.
15:01:59.189 [main] INFO  io.casperlabs.node.MetricsRuntime - Reporting metrics to Prometheus disabled.
15:01:59.191 [main] INFO  io.casperlabs.node.MetricsRuntime - Reporting metrics to Zipkin disabled.
15:01:59.192 [main] INFO  io.casperlabs.node.MetricsRuntime - Reporting metrics to JMX.
15:01:59.207 [main] INFO  kamon.metrics.SystemMetrics - Starting the Kamon(SystemMetrics) module
15:01:59.276 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC external server started at 78.144.212.177:40401
15:01:59.279 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC internal server started at 78.144.212.177:40402
15:01:59.634 [node-runner-20] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://44b75da6273cf966cd2e2f608c2061b4b95a926a@78.144.212.177?protocol=40400&discovery=40404.
```

##### 2.1.2.2 Running Node directly from Packages

This will run Node from a package that was built in [Building from source](#building-from-source).  Select the package for your system and install.

```console
$ ./node/target/universal/stage/bin/casperlabs-node run -s
15:03:55.420 [main] INFO  io.casperlabs.node.Main$ - CasperLabs node (3ec3baf422f0b8055df8d6dc0414664736a392c0)
15:03:55.428 [main] INFO  io.casperlabs.node.NodeEnvironment$ - Using data dir: /home/aakoshh/.casperlabs
15:03:55.565 [main] INFO  io.casperlabs.comm.UPnP$ - trying to open ports using UPnP....
```

## 3. Miscellaneous

### 3.1. Host and Port

The system attempts to find a gateway device with Universal Plug-and-Play enabled. If that fails, the system tries to guess a good IP address and a reasonable TCP port that other nodes can use to communicate with this one. If it does not guess a usable pair, they may be specified on the command line using the `--server-host` and `--server-port` options:

```console
--server-host 1.2.3.4 --server-port 40400
```

By default it uses TCP port 40400. This is also how more than one node may be run on a single machine: just pick different
ports. Remember that if using Docker, ports may have to be properly mapped and forwarded. For example, if we want to connect on the test net on TCP port 12345 and our machine's public IP address is 1.2.3.4, we could do it like so:

```console
$ docker run -ti -p 12345:12345 casperlabs/node:latest run -p 12345 --server-host 1.2.3.4
```

or perhaps by causing docker to use the host network and not its own bridge: Note: This does NOT work on MacOSX

```console
$ docker run -ti --network=host casperlabs/node:latest run -p 12345
```

This may take some experimentation to find combinations of arguments that work for any given setup.

Read more than you want to know about Docker networking starting about
[here](https://docs.docker.com/engine/userguide/networking/work-with-networks/), but honestly, it's featureful and powerful enough that you need a [cheatsheet](https://github.com/wsargent/docker-cheat-sheet#exposing-ports).


### 3.2 Bootstrapping a Private Network

It is possible to set up a private CasperLabs network by running a standalone node and using it for bootstrapping other nodes. Here we run one on port 4000:

```console
$ ./node/target/universal/stage/bin/casperlabs-node run -s -p 4000 --server-host 127.0.0.1 --server-no-upnp
...
17:34:22.025 [main] INFO  io.casperlabs.node.NodeRuntime - Starting stand-alone node.
...
17:34:22.291 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC external server started at 127.0.0.1:40401
17:34:22.292 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC internal server started at 127.0.0.1:40402
17:34:22.542 [main] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@127.0.0.1?protocol=4000&discovery=40404.
```

Now bootstrapping the other node just means giving the argument

```console
--server-bootstrap "casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@78.144.212.177?protocol=4000&discovery=40404"
```

For example:

```console
$ mkdir ~/.casperlabs-2
$ ./node/target/universal/stage/bin/casperlabs-node \
     --grpc-port 40501 \
     run \
     --server-data-dir ~/.casperlabs-2 \
     --server-port 40500 \
     --server-http-port 40503 \
     --server-kademlia-port 40504 \
     --grpc-port-internal 40502 \
     --server-bootstrap "casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@127.0.0.1?protocol=4000&discovery=40404" \
     --server-host 127.0.0.1 \
     --server-no-upnp
17:44:12.261 [main] INFO  io.casperlabs.node.Main$ - CasperLabs node (3ec3baf422f0b8055df8d6dc0414664736a392c0)
17:44:12.268 [main] INFO  io.casperlabs.node.NodeEnvironment$ - Using data dir: /home/aakoshh/.casperlabs-2
...
17:44:12.843 [main] INFO  io.casperlabs.node.NodeRuntime - Starting node that will bootstrap from casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@127.0.0.1?protocol=4000&discovery=40404
...
17:44:13.141 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC external server started at 127.0.0.1:40501
17:44:13.142 [main] INFO  io.casperlabs.node.NodeRuntime - gRPC internal server started at 127.0.0.1:40502
17:44:13.207 [main] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504.
17:44:13.704 [tl-dispatcher-49] INFO  i.c.c.util.comm.CasperPacketHandler$ - Valid ApprovedBlock received!
17:44:13.734 [tl-dispatcher-49] WARN  i.c.b.InMemBlockDagStorage - Block a5ef46913d95a4ebdc8e0d5c81db33bdec8a6521c8baf498350f24a56aa56ef3 sender is empty
17:44:13.750 [tl-dispatcher-49] INFO  i.c.c.util.comm.CasperPacketHandler$ - Making a transition to ApprovedBlockRecievedHandler state.
17:44:13.761 [tl-dispatcher-50] INFO  i.c.casper.util.comm.CommUtil$ - Requested fork tip from peers
17:44:22.649 [tl-dispatcher-58] INFO  io.casperlabs.comm.rp.Connect$ - Peers: 1.
17:44:22.650 [tl-dispatcher-58] INFO  i.casperlabs.comm.rp.HandleMessages$ - Responded to protocol handshake request from casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@127.0.0.1?protocol=4000&discovery=40404

```

where bootstrapped node should log that it has connected to the new one:

```console
17:44:13.442 [tl-dispatcher-45] INFO  i.c.c.util.comm.CasperPacketHandler$ - Received ApprovedBlockRequest from casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504
17:44:13.444 [tl-dispatcher-45] INFO  i.c.comm.transport.TcpTransportLayer - stream to List(casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504) blob
17:44:13.444 [tl-dispatcher-45] INFO  i.c.c.util.comm.CasperPacketHandler$ - Sending ApprovedBlock to casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504
17:44:13.663 [tl-dispatcher-47] INFO  i.c.comm.transport.TcpTransportLayer - Streamed packet /home/aakoshh/.casperlabs/tmp/comm/20190219174413_a43463ed_packet.bts to casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504
17:44:22.667 [loop-36] INFO  io.casperlabs.comm.rp.Connect$ - Peers: 1.
17:44:22.668 [loop-36] INFO  io.casperlabs.comm.rp.Connect$ - Connected to casperlabs://abd7498729d1a36f6d4921e3dd83ab1cf10552c8@127.0.0.1?protocol=40500&discovery=40504.
5df2d57}
```

Another option to set up local network is to look at the [docker](../docker/README.md) directory:

```console
$ make node-0/up
...
$ make node-1/up
...
$ docker logs -f node-0
...
16:03:25.576 [main] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://bc416197520ec34e19a03507f93bbeae15f80a02@node-0?protocol=40400&discovery=40404.
16:03:29.770 [node-runner-17] WARN  i.c.b.InMemBlockDagStorage - Block 170f89c15dabf138ecc4f20e9d171416b8a59fcc1e6b30945df69394ff984524 sender is empty
16:03:29.815 [node-runner-17] INFO  i.c.c.util.comm.CasperPacketHandler$ - Making a transition to ApprovedBlockRecievedHandler state.
16:03:29.849 [node-runner-46] INFO  i.c.casper.util.comm.CommUtil$ - Requested fork tip from peers
16:03:53.731 [tl-dispatcher-51] INFO  i.c.c.util.comm.CasperPacketHandler$ - Received ApprovedBlockRequest from casperlabs://a2ddce05242c9b9abfd4af0a303a88eb171748ec@node-1?protocol=40400&discovery=40404
16:03:53.733 [tl-dispatcher-51] INFO  i.c.comm.transport.TcpTransportLayer - stream to List(casperlabs://a2ddce05242c9b9abfd4af0a303a88eb171748ec@node-1?protocol=40400&discovery=40404) blob
16:03:53.734 [tl-dispatcher-51] INFO  i.c.c.util.comm.CasperPacketHandler$ - Sending ApprovedBlock to casperlabs://a2ddce05242c9b9abfd4af0a303a88eb171748ec@node-1?protocol=40400&discovery=40404
16:03:54.037 [tl-dispatcher-53] INFO  i.c.comm.transport.TcpTransportLayer - Streamed packet /root/.casperlabs/tmp/comm/20190219160353_291a78ec_packet.bts to casperlabs://a2ddce05242c9b9abfd4af0a303a88eb171748ec@node-1?protocol=40400&discovery=40404
16:04:25.750 [loop-42] INFO  io.casperlabs.comm.rp.Connect$ - Peers: 1.
16:04:25.752 [loop-42] INFO  io.casperlabs.comm.rp.Connect$ - Connected to casperlabs://a2ddce05242c9b9abfd4af0a303a88eb171748ec@node-1?protocol=40400&discovery=40404.
```

### 3.3 Metrics

The current version of the node produces metrics on some communications-related activities in Prometheus format.

To see metrics in action check out the [docker](../docker/README.md) setup. Once you run `make up` you should be able to see Prometheus metrics at http://localhost:9090 and Grafana at http://localhost:3000

### 3.4 Caveats

This is very much a work in progress. The networking overlay is only known to work when it can avail itself of visible IP addresses, either public or all contained within the same network. It does not yet include any special code for getting around a home firewall or a closed router, though it does contain some uPNP handling. Any port used must be open or mapped through the router. Depending on your setup, it might be necessary to configure port-forwarding on your router. In some cases, it might even be necessary to specify your router's public IP address as the node address if your router's port-forwarding requires it.


