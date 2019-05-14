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
You'll 2 sets of keys:
1) `secp256r1` private key encoded in unencrypted `PKCS#8` format and `SHA256 with ECDSA X.509` certificate. 
These will be used for node-to-node interaction. 
2) [`ed25519` or `secp256k1`](/DEVELOPER.md/run-the-node). These are used as your validator identity. See the link to how to generate them.

#### secp256r1
[Download and intstall the latest OpenSSL 1.1 version](/DEVELOPER.md/run-the-node)

Generate private key:
```bash
openssl ecparam -name secp256r1 -genkey -noout -out secp256r1-private.pem
openssl pkcs8 -topk8 -nocrypt -in secp256r1-private.pem -out secp256r1-private-pkcs8.pem
rm secp256r1-private.pem
```

Generate certificate from the generated private key:
```bash
openssl req -new -x509 -key secp256r1-private-pkcs8.pem -out node.cer -days 365
```

Now you can them as:
```bash
./node/target/universal/stage/bin/casperlabs-node run --tls-certificate node.cer --tls-key secp256r1-private-pkcs8.pem
```

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
     --casper-validator-public-key-path casperlabs-node-data/genesis/0/public-key.txt \
     --casper-validator-private-key-path casperlabs-node-data/genesis/0/private-key.txt \
     --grpc-socket casperlabs-node-data/.caspernode.sock
```


### Monitoring

You can add the `--metrics-prometheus` option in which case the node will collect metrics and make them available at `http://localhost:40403/metrics`. You can override that port with the `--server-http-port` option.

To see how an example of how to configure Prometheus and Grafana you can check out the [docker setup](docker/README.md#monitoring) or the [Prometheus docs](https://prometheus.io/docs/prometheus/latest/getting_started/).
