## Building the CasperLabs Node

### Prerequisites

* Java Development Kit (JDK), version 11
  * We recommend using the [OpenJDK](https://openjdk.java.net)
* [protoc](https://github.com/protocolbuffers/protobuf/releases), version 3.6.1 or greater
* [sbt](https://www.scala-sbt.org/download.html)
* [rustup](https://www.rust-lang.org/tools/install)

### Instructions

The following commands should be run from the root directory of the CasperLabs repo.

#### Build the node

```
make build-node
```

The `casperlabs-node` executable will be found here:

```
./node/target/universal/stage/bin/casperlabs-node
```

#### Build the client

```
make build-client
```

The `casperlabs-client` executable will be found here:

```
./client/target/universal/stage/bin/casperlabs-client
```

#### Build the casperlabs-engine-grpc-server

```
cd execution-engine
CARGO_FLAGS=--release make build
```

The `casperlabs-engine-grpc-server` executable will be found here:

```
./target/release/casperlabs-engine-grpc-server

```

#### Build the Mint and Proof-of-stake Contracts

This step is optional, the node will have the compiled wasm files packaged with it.

```
cd execution-engine
cargo build -p pos-install --release --target wasm32-unknown-unknown
cargo build -p mint-install --release --target wasm32-unknown-unknown
```

The compiled contracts will be found here:
```
./target/wasm32-unknown-unknown/release/pos_install.wasm
./target/wasm32-unknown-unknown/release/mint_install.wasm
```
