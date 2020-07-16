# Running the CasperLabs Node
---
The CasperLabs node consists of two components:
* `casperlabs-engine-grpc-server`, which executes smart contracts and persists the effects of these executions.
* `casperlabs-node`, which handles peer-to-peer communication, consensus, and block storage.

## Prerequisites

### Hardware
Base requirements: 4 cores, 16 GB RAM  and 200 GB of disk space.

Cloud Instances:

| Provider  | Machine Type  |
|---|---|
| AWS  | m5.xlarge  |
| Azure  | D4 v3  |
| GCP  | n1-standard-4  |

##### Note: It is advised to monitor disk usage and expand disk as necessary


### Operating System
* Ubuntu 18.04 LTS.  The software will work with other distributions, but you may experience some hiccups.  Feel free to reach out for help if you want to use another Linux distribution and hit issues. 


#### Using binaries (recommended):
* [Install](INSTALL.md) the `casperlabs` package, which contains `casperlabs-node` and `casperlabs-engine-grpc-server`.
* Create [keys](KEYS.md#generating-node-keys-and-validator-keys).

#### Ports used by the Node:

The default configuration of the node uses following ports:
* 40400 - Intra node communication port for consensus.
* 40401 - External GRPC for deployments
* 40403 - For monitoring (GraphQL, Grafana etc)
* 40404 - Intra node communication port for node discovery.

It is possible to override these settings by passing the `-port` flag when starting the node.

### Running a Read-Only Node on the Casper Testnet

##### Step 1: Create an account at [clarity.casperlabs.io](https://clarity.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will run the node.  This will be your validator account and keypair.


##### Step 2: Get the ChainSpec

The node needs the information that allows it to connect to Testnet. This information is known as the Chain specification (Chainspec). The Chainspec is comprised of a list of Genesis validators, stored in the `accounts.csv` file and a `manifest.toml`, which contains protocol parameters.  These files need to be placed in the `chainspec/genesis` directory on the node.
###### Testnet ChainSpec

These files are available from:(https://github.com/CasperLabs/CasperLabs/tree/dev/testnet). It is recommended that the files be downloaded via curl or equivalent mechanism, to avoid any hidden characters from appearing in the files.  The Genesis block must have the same hash, or the node will not connect.

```
mkdir -p ~/.casperlabs/chainspec/genesis

cd ~/.casperlabs/chainspec/genesis

curl -O https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/testnet/accounts.csv
curl -O https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/testnet/manifest.toml
```
##### Connecting elsewhere

To connect elsewhere, obtain the ChainSpec, unzip it, and start the node with the `--casper-chain-spec-path`
option pointed to the directory.

The ChainSpec contains the information to create the Genesis block.

##### Step 3: Start the Execution Engine

Note: The following instructions apply only for Linux OS.  Running the system using Docker requires adapting the commands for Docker.

```
casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

##### Step 4: Start the Node

In a separate terminal, run:
```
casperlabs-node run \
    --tls-key ./keys/node.key.pem \
    --tls-certificate ./keys/node.certificate.pem \
    --casper-validator-private-key-path ./keys/validator-private.pem \
    --casper-validator-public-key-path ./keys/validator-public.pem \
    --server-bootstrap "casperlabs://7dae5a7981bc9694616b5aac8fb7786797ce98ed@13.57.226.23?protocol=40400&discovery=40404 \ casperlabs://f2a46587e227428f38fa6f1e8f3c4749e8543783@52.53.252.92?protocol=40400&discovery=40404 \ casperlabs://4bd89b7dfa3eceea71f928ee895fbb2bf77481a9@13.52.217.79?protocol=40400&discovery=40404"
```

##### Checking Status

If your node has connected properly to an active network it will report a non-zero number of peers. There is a status endpoint that provides information on block height and synchronization. The endpoint outputs JSON.  Install JQuery for readable output, then run:

```
curl https://localhost:40403/status | jq
```

##### Stopping the Node

```
pkill casperlabs-node
pkill casperlabs-engine-grpc-server
```
To clear the previous state from the node run the following command:

```
cd ~/.casperlabs
rm sqlite.db
rm -r global_state
```

### Running a Standalone Node

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
```

##### Step 4: Deploy Some Code

See instructions [here](https://docs.casperlabs.io/en/latest/dapp-dev-guide/deploying-contracts.html).

### Running a Simulated Network

See instructions [here](../hack/docker/README.md).
