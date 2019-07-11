# Running the CasperLabs Node

The CasperLabs node consists of two components:
* `casperlabs-engine-grpc-server`, which executes smart contracts and persists the effects of these executions.
* `casperlabs-node`, which handles peer-to-peer communication, consensus, and block storage.

## Prerequisites

#### Using binaries (recommended):
* [Install](INSTALL.md) the `casperlabs` package, which contains `casperlabs-node` and `casperlabs-engine-grpc-server`.
* Download and unzip the [Mint and Proof-of-Stake Contracts](http://repo.casperlabs.io/casperlabs/repo/dev/blessed-contracts.tar.gz).
* Create [keys](KEYS.md#generating-node-keys-and-validator-keys).

#### Building from source:
* Build the [`casperlabs-node`](BUILD.md#build-the-node).
* Build the [`casperlabs-engine-grpc-server`](BUILD.md#build-the-casperlabs-engine-grpc-server).
* Build the [Mint and Proof-of-Stake Contracts](BUILD.md#build-the-mint-and-proof-of-stake-contracts).
* Create [keys](KEYS.md#generating-node-keys-and-validator-keys).

If you build from source, you will need to add the build directories to your `PATH`, for example:
```
export PATH="<path-to-CasperLabs-repo>/node/target/universal/stage/bin:$PATH"
export PATH="<path-to-CasperLabs-repo>/execution-engine/target/release:$PATH"
```
Or you can run the following commands from the root directory of the repo, using explicit paths to the binaries.

## Instructions

### Running a validator on the CasperLabs Network

##### Step 1: Create an account at [explorer.casperlabs.io](https://explorer.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will run the node.  This will be your validator account and keypair.

##### Step 2: Add coins to this account

Add coins to this account using the [faucet](https://explorer.casperlabs.io/#/faucet).

##### Step 3: Bond your validator onto the network

```
casperlabs-client \
    --host deploy.casperlabs.io 
    bond 
    --amount 1000 
    --nonce 1 
    --private-key <path-to-private-key>
```

##### Step 4: Start the Node

```
casperlabs-node run \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-genesis-account-public-key-path ./keys/validator-public.pem \
    --server-bootstrap "casperlabs://a605c8ddc4ed3dc9b881bfe006cc8175fb31e125@100.24.117.48?protocol=40400&discovery=40404"
```

##### Stopping a bonded validator 

First, you must unbond:
```
casperlabs-client \
    --host deploy.casperlabs.io 
    unbond 
    --amount 1000 
    --nonce 1 
    --private-key <path-to-private-key>
```

After that, you can safely stop the node:
```
pkill casperlabs-node
```

### Running a single Node

You can run a single Node in standalone mode for testing purposes.

##### Step 1: Create a `bonds.txt` file

```
mkdir -p ~/.casperlabs/genesis
(cat keys/validator-id; echo " 100") >> ~/.casperlabs/genesis/bonds.txt
```

##### Step 2: Start the Execution Engine

```
casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

##### Step 3: Start the Node

```
casperlabs-node run \
    --standalone \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-genesis-account-public-key-path ./keys/validator-public.pem \
    --casper-initial-tokens 1234567890 \
    --casper-mint-code-path ./mint_token.wasm \
    --casper-pos-code-path ./pos.wasm
```

##### Step 4: Deploy Some Code

See instructions [here](CONTRACTS.md).

### Running a Simulated Network

See instructions [here](../hack/docker/README.md).

