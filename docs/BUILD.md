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
sbt node/universal:stage
```

The `casperlabs-node` executable will be found here:

```
./node/target/universal/stage/bin/casperlabs-node
```

#### Build the client

```
sbt client/universal:stage
```

The `casperlabs-client` executable will be found here:

```
./client/target/universal/stage/bin/casperlabs-client
```

#### Build the casperlabs-engine-grpc-server

```
cd execution-engine
cargo build --release
```

The `casperlabs-engine-grpc-server` executable will be found here:

```
./target/release/casperlabs-engine-grpc-server

```

#### Build the Mint and Proof-of-stake Contracts

```
cd execution-engine
cargo build -p pos --release --target wasm32-unknown-unknown
cargo build -p mint-token --release --target wasm32-unknown-unknown
```

The compiled contracts will be found here:
```
./target/wasm32-unknown-unknown/release/pos.wasm
./target/wasm32-unknown-unknown/release/mint_token.wasm
```
