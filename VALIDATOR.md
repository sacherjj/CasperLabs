## Validator's Guide to Running a CasperLabs Node

Pre-packaged binaries are published to http://repo.casperlabs.io/casperlabs/repo. The following are an example of installing the node on Ubuntu.

### Prerequisites

* [OpenJDK](https://openjdk.java.net) Java Development Kit (JDK) or Runtime Environment (JRE), version 11. We recommend using the OpenJDK

```console
sudo add-apt-repository ppa:openjdk-r/ppa
sudo apt update
sudo apt install openjdk-11-jdk
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

Check that you have the right Java version:

```console
sudo update-alternatives --config java
```

You see the next output, we choose the `0th` selection here:
```console
There are 3 choices for the alternative java (providing /usr/bin/java).

  Selection    Path                                            Priority   Status
------------------------------------------------------------
  0            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      auto mode
  1            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      manual mode
  2            /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java   1081      manual mode
* 3            /usr/lib/jvm/java-8-oracle/jre/bin/java          1081      manual mode

Press <enter> to keep the current choice[*], or type selection number: 0
update-alternatives: using /usr/lib/jvm/java-11-openjdk-amd64/bin/java to provide /usr/bin/java (java) in auto mode
```

Print the Java version:
```console
java -version
```

The output should say we use the version 11:
```console
openjdk version "11.0.1" 2018-10-16
OpenJDK Runtime Environment (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1)
OpenJDK 64-Bit Server VM (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1, mixed mode, sharing)
```


### Installing from Debian packages

The node consists of an API component running in Java and an execution engine running the WASM code of the deploys. They have to be started separately at the moment and configured to talk to each other.

*NOTE: Users will need to update \[VERSION\] with the version the want. See:

```console
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
mkdir casperlabs-node-data
casperlabs-engine-grpc-server casperlabs-node-data/.caspernode.sock
```

The next output should be printed:
```console
Server is listening on socket: casperlabs-node-data/.caspernode.sock
```

#### --loglevel

The execution engine supports an optional `--loglevel` command line argument following the mandatory socket argument,
which sets the log level for the execution engine.

```console
casperlabs-engine-grpc-server casperlabs-node-data/.caspernode.sock --loglevel=error
```

The log levels supported are:

```
    --loglevel=
    fatal : critical problems that result in the execution engine crashing
    error : recoverable errors
    warning : unsuccessful but not erroneous activity
    info : normal, expected activity
    metric : execution durations, counts, and similar data points (verbose)
    debug : developer messages
```

The execution engine will log messages at the configured log level or above (thus, `error` will log errors and fatals but not warnings and below) to stdout.

If the `--loglevel` argument is not provided, the execution engine defaults to the `info` log level.

### Setting up keys
1. `secp256r1` (required) private key encoded in unencrypted `PKCS#8` format and `X.509` certificate. Used for node-to-node interaction.
2. `ed25519` (optional) private and public keys. Used as a validator identity. If not provided then a node starts in the read-only mode.
3. `ed25519` (optional) another set of private and public keys used by dApp developers to sign their deploys.

#### Generate keys using Docker
This is the easiest way to generate the keys. We'll make use of [a Docker image](/hack/key-management/Dockerfile) which will generate the keys. With this way we don't need installing required cryptography libraries on your machine keeping it cleaner. 

If you still want to generate the keys directly on your operating system without Docker then checkout [this guide](#generate-keys-on-host-os).

##### Prerequisites: Docker
Make sure you have Docker installed:
```console
docker version
```

You should the output similar to:
```console
Client: Docker Engine - Community
 Version:           18.09.2
 API version:       1.39
 Go version:        go1.10.8
 Git commit:        6247962
 Built:             Sun Feb 10 04:12:39 2019
 OS/Arch:           darwin/amd64
 Experimental:      false

Server: Docker Engine - Community
 Engine:
  Version:          18.09.2
  API version:      1.39 (minimum version 1.12)
  Go version:       go1.10.6
  Git commit:       6247962
  Built:            Sun Feb 10 04:13:06 2019
  OS/Arch:          linux/amd64
  Experimental:     true
```

If console returns `command not found` checkout [the official guide for installing Docker](https://docs.docker.com/install/).

When you have Docker installed we can start generating keys. 
For convenience we provide [a script](/hack/key-management/docker-gen-keys.sh) which will try to download the Docker image with cryptography libraries and create the keys.

Use the script as follow:
```console
mkdir keys
./keys-management/docker-gen-keys.sh keys
```

In a case of success you'll see the next output:
```console
using curve name prime256v1 instead of secp256r1
read EC key
Generate keys: Success
```

We can list the generated keys:
```
ls -1 keys
```

Output:
```console
node-id
node.certificate.pem
node.key.pem
validator-id
validator-private.pem
validator-public.pem
```

As you see we have keys and some additional files:
* `node-id` - Node ID is used for differentiating nodes and used as an ID in casperlabs nodes' addresses:
`casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404`
The address above contains `c0a6c82062461c9b7f9f5c3120f44589393edf31` as a node ID.
* `validator-id` - Validator ID is used to differentiate validators over a network. 
We need it because validators can switch machines keeping their identities. 
Also it's used for initial bonds granting during the genesis procedure of setting up a new network.

To run a node with the above keys we'll firstly need to create a bonds.txt file as follows:
```console
(cat keys/validator-id; echo " 100") >> bonds.txt
```

Now we can run a node as follows:
```console
casperlabs-node run -s \
    --tls-key keys/node.key.pem  \
    --tls-certificate keys/node.certificate.pem \
    --casper-validator-public-key-path keys/validator-public.pem \
    --casper-validator-private-key-path keys/validator-private.pem
```

#### Generate keys on host OS
**NOTE**: This guide is for advanced users, make sure you've read [the previous guide](#generate-keys-using-docker) before following this one.

In this guide keys will be generated on host OS which requires installing cryptography libraries.

##### Prerequisites: OpenSSL

[OpenSSL](https://www.openssl.org) is a general-purpose cryptography library we'll use to generate the keys.

**macOS:**
Ensure if [Brew](https://brew.sh) is installed:
```console
brew --version
```

It should print something like below if it's already installed:
```console
Homebrew 2.1.5
Homebrew/homebrew-core (git revision 8c8ee; last commit 2019-06-13)
Homebrew/homebrew-cask (git revision 76d0e; last commit 2019-06-13)
```

If console prints `bash: command not found` then install it:
```console
/usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
```

Install OpenSSL:
```console
brew update
brew install openssl@1.1
```

The next step depends on which shell is used on the machine:

Most likely you'll have the `bash` shell if you didn't override it with a custom ones:
```console
echo "" >> ~/.bashrc
echo 'export PATH="/usr/local/opt/openssl@1.1/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

`zsh`:
```console
echo "" >> ~/.bashrc
echo 'export PATH="/usr/local/opt/openssl@1.1/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Ensure the latest 1.1 version of OpenSSL is installed:
```console
openssl version
```

The output should say a 1.1 version is used:
```console
OpenSSL 1.1.1c  28 May 2019
```

**Linux:**

Download and install the latest version of the [openssl 1.1](https://github.com/openssl/openssl/releases).
```console
cd /tmp
curl -L https://github.com/openssl/openssl/archive/OpenSSL_1_1_1b.tar.gz -o openssl.tar.gz
tar -xzf openssl.tar.gz
cd openssl-OpenSSL_1_1_1b
./config
make
make test
sudo make install
sudo ldconfig
```

#### Prerequisites: sha3sum
Download and install the latest version of the [sha3sum](https://github.com/maandree/sha3sum). `sha3sum` is a cryptography library allowing to compute `Keccak`, `SHA-3`, `SHAKE` and `RawSHAKE` hashes.

1. macOS: `brew install sha3sum`
2. Ubunt 18.04:

 Build libkeccak:

```console
cd /tmp
git clone https://github.com/maandree/libkeccak.git
cd libkeccak
make
sudo make install
sudo ldconfig
```

 Build sha3sum:

```console
cd /tmp
git clone https://github.com/maandree/sha3sum.git
cd sha3sum
make
sudo make install
```

#### Script
You may want to use [the script](/hack/key-management/gen-keys.sh) which will generate all the keys. Make sure the [OpenSSL](/VALIDATOR.md#prerequisites-openssl) and [sha3sum](/VALIDATOR.md#prerequisites-sha3sum) are installed before running the script. The commands below are excerpts from this script.

#### ed25519 Validator
Generate private key:
```console
openssl genpkey -algorithm Ed25519 -out ed25519-validator-private.pem
```

If the commands returns the next message `Algorithm Ed25519 not found` it means that you don't have the latest version of OpenSSL installed.
Generally, if any error occurs firstly make sure if the [OpenSSL](/VALIDATOR.md#prerequisites-openssl) and [sha3sum](/VALIDATOR.md#prerequisites-sha3sum) are installed.

Public key:
```console
openssl pkey -in ed25519-validator-private.pem -pubout -out ed25519-validator-public.pem
```

Use the public key to create a bonds.txt file which contains a set of initial validators of a network and their initial bonds:
```console
VALIDATOR_ID=$(openssl pkey -outform DER -pubout -in ed25519-validator-private.pem | tail -c +13 | openssl base64)
echo "$VALIDATOR_ID" " 100" > bonds.txt
```

Use them as follow:
```console
casperlabs-node run -s \
    --casper-validator-private-key-path ed25519-validator-private.pem \
    --casper-validator-public-key-path ed25519-validator-public.pem \
    --casper-bonds-file bonds.txt
```

#### ed25519 dApp Developer
Generate private key:
```console
openssl genpkey -algorithm Ed25519 -out ed25519-developer-private.pem
```

If the commands returns the next message `Algorithm Ed25519 not found` it means that you don't have the latest version of OpenSSL installed.
Generally, if any error occurs firstly make sure if the [OpenSSL](/VALIDATOR.md#prerequisites-openssl) and [sha3sum](/VALIDATOR.md#prerequisites-sha3sum) are installed.

Public key:
```console
openssl pkey -in ed25519-developer-private.pem -pubout -out ed25519-developer-public.pem
```

Use them sign a deploy as:
```console
casperlabs-node --host <node hostname> deploy \
    --public-key ed25519-developer-public.pem \
    --private-key ed25519-developer-public.pem \
    --from <purse address that will be used to pay for the deployment> \
    --gas-price <The price of gas for this transaction in units dust/gas> \
    --nonce <The counter that should be incremented during each deploy> \
    --session <path to the file with session code> \
    --payment <path to the file with payment code>
```

#### secp256r1

Generate private key:
```console
openssl ecparam -name secp256r1 -genkey -noout -out secp256r1-private.pem
openssl pkcs8 -topk8 -nocrypt -in secp256r1-private.pem -out secp256r1-private-pkcs8.pem
rm secp256r1-private.pem
```

Obtain node ID from the private key:
```console
NODE_ID=$(cat secp256r1-private-pkcs8.pem | \
    openssl ec -text -noout | \
    grep pub -A 5 | \
    tail -n +2 | \
    tr -d '\n[:space:]:' | \
    sed 's/^04//' | \
    keccak-256sum -x -l | \
    tr -d ' -' | \
    tail -c 41 | \
    tr -d '\n')
```

Node ID is used for differentiating different nodes and used as an ID in casperlabs nodes' addresses:
```
casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE IP OR HOSTNAME ADDRESS>?protocol=40400&discovery=40404
```
The above address contains `c0a6c82062461c9b7f9f5c3120f44589393edf31` as a node ID.

Generate certificate from the generated private key. Fill asked questions and enter the above `NODE_ID` as a `Common Name (CN)`
```console
openssl req \
    -new \
     -x509 \
     -key secp256r1-private-pkcs8.pem \
     -out node.certificate.pem \
     -days 365 \
```

Now you can use them as:
```console
casperlabs-node run \
    --tls-certificate node.certificate.pem \
    --tls-key secp256r1-private-pkcs8.pem
```

### Configuring networking

With the keys at hand you can start the node again, but this time configure it to be reachable on the network. [UPnP](https://casperlabs.atlassian.net/wiki/spaces/EN/pages/38928385/Node+Supported+Network+Configuration?atlOrigin=eyJpIjoiOTNmZjI2ZDllYmMxNGM1NmIwMzVjNmRlNTAyNzU2M2QiLCJwIjoiYyJ9) might be able to discover your public IP and open firewall rules in your router, or you may have to do it manually.

If you do it manually, you need to find out your externally visible IP address. You'll have to set this using the `--server-host <ip>` option so the node advertises itself at the right address.

The node will listen on multiple ports; the following are the default values, you don't need to specify them, but they're shown with the command line option you can use to override:
* `--server-port 40400`: Intra node communication port for consensus.
* `--grpc-port-external 40401`: Port to accept deploys on.
* `--grpc-port-internal 40402`: Port reserved for operator, to propose blocks for example.
* `--server-http-port 40403`: Port to use for HTTP services such as metrics and GraphQL.
* `--server-kademila-port 40404`: Intra node communication port for node discovery.

Further options include:
* `--server-use-gossiping`: Turn on more efficient intra-node communications stack.
* `--grpc-use-tls`: Use TLS encryption on the API endpoints used for sending deploys and proposing blocks.

When the `--grpc-use-tls` option is turned on, the `casperlabs-client` needs the `--node-id` option set to the value of the Keccak256 hash of the public key of the node. This is the same value as `NODE_ID`, so as a validator this information can be given to other nodes as well as dApp developers.

An alternative way to obtain it is to use `openssl` to retrieve it from a remote host. The following script would pull the certificate from the local server and save it to a file:

```console
openssl s_client -showcerts -connect localhost:40401 </dev/null 2>/dev/null \
  | openssl x509 -outform PEM \
  > node.crt
```

You can run the code snippet that [generated](VALIDATOR.md#secp256r1) the `NODE_ID` on that file to compute the hash.


### Configure auto-proposal

It is possible to call the `propose` command to manually trigger block creation but this is best reserved for demonstrational purposes. The recommended way to run a node at the moment is to turn on the simple auto-proposal feature that will try to create a block if a certain number of deploys have accumulated or the oldest has been sitting in the buffer for longer than a threshold:
* `--auto-propose-enabled true`
* `--auto-propose-max-interval 5seconds`
* `--auto-propose-max-count 10`


### Starting the node

We'll have use the same socket to start the node as the one we used with the execution engine.

You can start the node in two modes:
* `-s` puts it in standalone mode, which means it will generate a genesis block on its own
* Without `-s` you have to use the `--server-bootstrap` option and give it the address of another node to get the blocks from and to start discovering other nodes with. The address is in the form of `casperlabs://<bootstrap-node-id>@$<bootstrap-node-ip-address>?protocol=40400&discovery=40404`, but the ports can be different, based on what the operator of the node configured.

```console
casperlabs-node \
     --grpc-port 40401 \
     run \
     --server-data-dir casperlabs-node-data \
     --server-port 40400 \
     --server-kademlia-port 40404 \
     --server-bootstrap "<bootstrap-node-address>" \
     --server-host <external-ip-address> \
     --server-no-upnp \
     --tls-certificate node.certificate.pem \
     --tls-key secp256r1-private-pkcs8.pem \
     --casper-validator-private-key-path ed25519-private.pem \
     --casper-validator-public-key-path ed25519-public.pem \
     --grpc-socket casperlabs-node-data/.caspernode.sock \
     --casper-auto-propose-enabled
```


### Monitoring

You can add the `--metrics-prometheus` option in which case the node will collect metrics and make them available at `http://localhost:40403/metrics`. You can override that port with the `--server-http-port` option.

To see how an example of how to configure Prometheus and Grafana you can check out the [docker setup](hack/docker/README.md#monitoring) or the [Prometheus docs](https://prometheus.io/docs/prometheus/latest/getting_started/).
