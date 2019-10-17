# Running the CasperLabs Node
---
The CasperLabs node consists of two components:
* `casperlabs-engine-grpc-server`, which executes smart contracts and persists the effects of these executions.
* `casperlabs-node`, which handles peer-to-peer communication, consensus, and block storage.

## Prerequisites

#### Using binaries (recommended):
* [Install](INSTALL.md) the `casperlabs` package, which contains `casperlabs-node` and `casperlabs-engine-grpc-server`.
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

##### Step 1: Create an account at [clarity.casperlabs.io](https://clarity.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will run the node.  This will be your validator account and keypair.

##### Step 2: Add coins to this account

Add coins to this account using the [faucet](https://clarity.casperlabs.io/#/faucet).

##### Step 3: Bond your validator onto the network

```
casperlabs-client \
    --host deploy.casperlabs.io \
    bond \
    --payment-amount 1 \
    --amount <bond-amount> \
    --private-key <path-to-private-key>
```

Note: `--payment-amount` is used in the standard payment code to pay for the execution.
For now, the value is not used, but payment code will be enabled on the DEVNET
in an upcoming release.

##### Step 4: Download the ChainSpec

The node comes with the ChainSpec contracts that should allow you to connect to DevNet. You will also need to pull down the latest `accounts.csv` and store this in the `chainspec/genesis` directory for your node if you wish to connect to DevNet. You can download DevNet's `account.csv` from our [releases](https://github.com/CasperLabs/CasperLabs/releases) page starting with Release `v0.8.0`. To connect elsewhere,
you need to obtain the ChainSpec, unzip it, and start the node with the `--casper-chain-spec-path`
option pointed to the directory.

The ChainSpec contains the system contracts, but if you downloaded or built them separately you need to copy them. You can override the defaults packaged in the node by copying the system contracts to
`~/.casperlabs/chainspec/genesis/`, or wherever the `--server-data-dir` is pointing, which by default is `~/.casperlabs`.

##### Step 5: Start the Execution Engine

```
casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

##### Step 6: Start the Node

In a separate terminal, run:
```
casperlabs-node run \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-validator-public-key-path ./keys/validator-public.pem \
    --server-bootstrap "casperlabs://a605c8ddc4ed3dc9b881bfe006cc8175fb31e125@100.24.117.48?protocol=40400&discovery=40404"
```

##### Stopping a bonded validator

First, you must unbond:
```
casperlabs-client \
    --host deploy.casperlabs.io \
    unbond \
    --payment-amount 1 \
    --amount <unbond-amount> \
    --private-key <path-to-private-key>
```

The `--amount` argument here is optional: you can partially unbond by providing an amount that is smaller than your bond amount, or you can omit this argument to unbond your full bond amount.
(See note above about `--payment-amount`).

After that, you can safely stop processes:
```
pkill casperlabs-node
pkill casperlabs-engine-grpc-server
```

### Running a single Node

You can run a single Node in standalone mode for testing purposes.

##### Step 1: Create an `accounts.csv` file

Add your validator key as the single bonded validator to the accounts in the ChainSpec.
You can override the default accounts that come with the node by shadowing the file
under your `--server-data-dir` directory, by default `~/.casperlabs`. For example the
following code would cause your validator to have an initial balance of 50 million and
a 1 million in bonds.

```
mkdir -p ~/.casperlabs/chainspec/genesis
(cat keys/validator-id; echo ",50000000,1000000") > ~/.casperlabs/chainspec/genesis/accounts.csv
```

##### Step 2: Start the Execution Engine

In a separate terminal, run:
```
casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

##### Step 3: Start the Node

```
casperlabs-node run \
    --casper-standalone \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-validator-public-key-path ./keys/validator-public.pem \
    --casper-initial-motes 1234567890 \
    --casper-mint-code-path ./mint_token.wasm \
    --casper-pos-code-path ./pos.wasm
```

##### Step 4: Deploy Some Code

See instructions [here](CONTRACTS.md).

### Running a Simulated Network

See instructions [here](../hack/docker/README.md).
