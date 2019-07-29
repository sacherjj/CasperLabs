# Deploying Contracts

## Prerequisites

#### Using binaries (recommended):
* Install [`rustup`](https://rustup.rs/).
* Install the [`casperlabs`](INSTALL.md) package, which contains `casperlabs-client`.

#### Building from source:
* Install [`rustup`](https://rustup.rs/).
* Build the [`casperlabs-client`](BUILD.md#build-the-client).

If you build from source, you will need to add the build directories to your `PATH`, for example:
```
export PATH="<path-to-CasperLabs-repo>/client/target/universal/stage/bin:$PATH"
```
Or you can run the client commands from the root directory of the repo, using explicit paths to the binaries.

## Instructions

##### Step 1: Clone the [examples](https://github.com/CasperLabs/contract-examples) and set up your toolchain
```
git clone git@github.com:CasperLabs/contract-examples.git
cd contract-examples
rustup toolchain install $(cat rust-toolchain)
rustup target add --toolchain $(cat rust-toolchain) wasm32-unknown-unknown
```

##### Step 2: Build the example contracts
```
cargo build --release
export COUNTER_DEFINE="$(pwd)/target/wasm32-unknown-unknown/release/counterdefine.wasm"
export COUNTER_CALL="$(pwd)/target/wasm32-unknown-unknown/release/countercall.wasm"
```

##### Step 3: Create an account at [explorer.casperlabs.io](https://explorer.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will deploy contracts.

##### Step 4: Add coins to this account

Add coins to this account using the [faucet](https://explorer.casperlabs.io/#/faucet).

##### Step 5: Deploy `counterdefine.wasm`
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_DEFINE \
    --nonce <nonce>
```
For each account, for your first deploy, the `--nonce` argument must be 1.  See note [below](#a-note-about-nonces).

You should see the following output:
```
Success!
```

##### Step 6: Observe

See the instructions [here](QUERYING.md).


##### Step 7: Deploy `countercall.wasm`
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_CALL \
    --nonce <nonce>
```
For each account, for your second deploy, the `--nonce` argument must be 2.  See note [below](#a-note-about-nonces).

You should see the following output:
```
Success!
```

##### Step 8: Observe

See the instructions [here](QUERYING.md).

#### A note about nonces

For each account you have, you must keep track of the nonces you use and increment the nonce by 1 for each subsequent deploy, bonding request, or unbonding request you make.

#### Using a local standalone node

If you are testing with a [local standalone node](NODE.md#running-a-single-node), you will need to change the `--host` argument:

```
casperlabs-client \
    --host 127.0.0.1 \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_DEFINE \
    --nonce <nonce>
```

You will also need to explicitly propose after making a deploy (or several deploys), in order for your deploys to be committed:

```
casperlabs-client --host 127.0.0.1 propose
```
