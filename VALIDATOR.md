## Validator's Guide to Running a CasperLabs Node

Pre-packaged binaries are published to http://repo.casperlabs.io/casperlabs/repo. The following are an example of installing the node on Ubuntu.

### Prerequisites

* [OpenJDK](https://openjdk.java.net) Java Development Kit (JDK) or Runtime Environment (JRE), version 11. We recommend using the OpenJDK

```sh
sudo add-apt-repository ppa:openjdk-r/ppa
sudo apt update
sudo apt install openjdk-11-jdk
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

Check that you have the right Java version:

```console
$ sudo update-alternatives --config java
There are 3 choices for the alternative java (providing /usr/bin/java).

  Selection    Path                                            Priority   Status
------------------------------------------------------------
  0            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      auto mode
  1            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      manual mode
  2            /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java   1081      manual mode
* 3            /usr/lib/jvm/java-8-oracle/jre/bin/java          1081      manual mode

Press <enter> to keep the current choice[*], or type selection number: 0
update-alternatives: using /usr/lib/jvm/java-11-openjdk-amd64/bin/java to provide /usr/bin/java (java) in auto mode
$ java -version
openjdk version "11.0.1" 2018-10-16
OpenJDK Runtime Environment (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1)
OpenJDK 64-Bit Server VM (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1, mixed mode, sharing)
```


### Installing from Debian packages

The node consists of an API component running in Java and an execution engine running the WASM code of the deploys. They have to be started separately at the moment and configured to talk to each other.

*NOTE: Users will need to update \[VERSION\] with the version the want. See:

```sh
curl -sO http://repo.casperlabs.io/casperlabs/repo/master/casperlabs-node_[VERSION]_all.deb
curl -sO http://repo.casperlabs.io/casperlabs/repo/master/casperlabs-engine-grpc-server_[VERSION]_amd64.deb
sudo dpkg -i casperlabs-node_[VERSION]_all.deb
sudo dpkg -i casperlabs-engine-grpc-server_[VERSION]_amd64.deb
```

After these steps you should be able to run `casperlabs-node --help` and `casperlabs-engine-grpc-server --help`.


### Starting the execution engine

The execution engine runs as a separate process and isn't open to the network, it communicates with the node through a UNIX file socket. If you're using Windows it will have to run under Windows Subsystem for Linux (WSL).

The node will want to connect to this socket, so it's best to start the engine up front.

```console
$ mkdir casperlabs-node-data
$ casperlabs-engine-grpc-server casperlabs-node-data/.caspernode.sock
Server is listening on socket: casperlabs-node-data/.caspernode.sock
```


### Setting up keys

As a validator you'll need a public and private key to sign blocks, and an SSL certificate to provide secure communication with other nodes on the network. You can run the node once to generate these up front.

You'll need to create a directory to hold data. By default this is expected to be at `~/.casperlabs`, but you can provide another location with the `--server-data-dir` option.

```console
$ casperlabs-node run -s \
    --server-data-dir casperlabs-node-data \
    --grpc-socket casperlabs-node-data/.caspernode.sock \
    --casper-num-validators 1

10:40:19.729 [main] INFO  io.casperlabs.node.Main$ - CasperLabs node (030bb96133ef9a9c31133ab3371937c2388bf5b9)
10:40:19.736 [main] INFO  io.casperlabs.node.NodeEnvironment$ - Using data dir: /home/aakoshh/projects/casperlabs-node-data
10:40:19.748 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - No certificate found at path /home/aakoshh/projects/casperlabs-node-data/node.certificate.pem
10:40:19.749 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a X.509 certificate for the node
10:40:19.752 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a PEM secret key for the node
...
10:40:23.788 [main] INFO  i.c.c.util.comm.CasperPacketHandler$ - Starting in create genesis mode
10:40:24.077 [main] WARN  i.casperlabs.casper.genesis.Genesis$ - Specified bonds file None does not exist. Falling back on generating random validators.
10:40:24.088 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd with bond 1
...
10:40:24.132 [main] WARN  i.c.casper.ValidatorIdentity$ - No private key detected, cannot create validator identification.
...
10:40:24.944 [main] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://e1af07bebc9f88e399efe816ac06dfc6b82f7783@78.144.212.177?protocol=40400&discovery=40404.
...
^C
$ tree casperlabs-node-data
casperlabs-node-data
├── casperlabs-node.log
├── genesis
│   ├── 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk
│   └── bonds.txt
├── node.certificate.pem
├── node.key.pem
└── tmp
    └── comm
        ├── 20190222104024_8091beb0_packet.bts
        └── 20190222104024_f6a0cda2_packet.bts

3 directories, 7 files
```

Once the server starts listening to traffic you can kill it with `Ctrl+C` and see that it creted some files:
* `node.certificate.pem` and `node.key.pem` are the SSL certificates it's going to use on subsequent starts. They correspond to the address `casperlabs://e1af07bebc9f88e399efe816ac06dfc6b82f7783@78.144.212.177?protocol=40400&discovery=40404` printed in the logs, in particular the value `e1af0...783` is going to the the node ID, which is a hash of its public key.
* `genesis/09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk` is the validator key. The value `9c47347...7bd` is the public key itself, the file content is the private key.
* `genesis/bonds.txt` will have the public key with a weight in it. In normal mode you'd get the bonds from the bootstrap node and the latest blocks, however currently the node relies on static configuration.

__NOTE__: Your values will be different from the ones shown above.


### Configuring networking

With the keys at hand you can start the node again, but this time configure it to be reachable on the network. [UPnP](https://casperlabs.atlassian.net/wiki/spaces/EN/pages/38928385/Node+Supported+Network+Configuration?atlOrigin=eyJpIjoiOTNmZjI2ZDllYmMxNGM1NmIwMzVjNmRlNTAyNzU2M2QiLCJwIjoiYyJ9) might be able to discover your public IP and open firewall rules in your router, or you may have to do it manually.

If you do it manually, you need to find out your externally visible IP address. You'll have to set this using the `--server-host <ip>` option so the node advertises itself at the right address.

The node will listen on multiple ports; the following are the default values, you don't need to specify them, but they're shown with the command line option you can use to override:
* `--grpc-port 40401`: Port to accept deploys on.
* `--server-port 40400`: Intra node communication port for consensus.
* `--server-kademila-port 40404`: Intra node communication port for node discovery.



### Starting the node

We'll have use the same socket to start the node as the one we used with the execution engine.

You can start the node in two modes:
* `-s` puts it in standalone mode, which means it will generate a genesis block on its own
* Without `-s` you have to use the `--server-bootstrap` option and give it the address of another node to get the blocks from and to start discovering other nodes with. The address is in the form of `casperlabs://<bootstrap-node-id>@$<bootstrap-node-ip-address>?protocol=40400&discovery=40404`, but the ports can be different, based on what the operator of the node configured.

```console
$ casperlabs-node \
     --grpc-port 40401 \
     run \
     --server-data-dir casperlabs-node-data \
     --server-port 40400 \
     --server-kademlia-port 40404 \
     --server-bootstrap "<bootstrap-node-address>" \
     --server-host <external-ip-address> \
     --server-no-upnp \
     --casper-validator-public-key 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd \
     --casper-validator-private-key-path casperlabs-node-data/genesis/09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk \
     --grpc-socket casperlabs-node-data/.caspernode.sock
```


### Monitoring

You can add the `--metrics-prometheus` option in which case the node will collect metrics and make them available at `http://localhost:40403/metrics`. You can override that port with the `--server-http-port` option.

To see how an example of how to configure Prometheus and Grafana you can check out the [docker setup](docker/README.md#monitoring) or the [Prometheus docs](https://prometheus.io/docs/prometheus/latest/getting_started/).
