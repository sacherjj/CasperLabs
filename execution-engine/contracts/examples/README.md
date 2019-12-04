# CasperLabs Smart Contract Examples

Each subdirectory contains an example of a smart contract definition and a companion contract that calls it.

## Prerequisites

* [`rustup`](https://rustup.rs/)

After installing `rustup`, run the following commands from the root of this repo:

```
rustup toolchain install $(cat rust-toolchain)
rustup target add --toolchain $(cat rust-toolchain) wasm32-unknown-unknown
```

## Building

To build all the contracts:

```
# Navigate to execution-engine subdirectory of CasperLabs git repo
cd ../../execution-engine
make build-example-contracts
```

To build a specific contract and its companion:

```
cargo build --target wasm32-unknown-unknown --release -p hello-name-define -p hello-name-call
```

After building a contract, you will find the corresponding wasm file in `target/wasm32-unknown-unknown/release` which is located in the Cargo workspace root directory. Relatively to the directory of this readme those files will be placed in `../../target`. Currently all our test and example contracts are placed in a single Cargo workspace.

**NOTE**: The `--release` flag is currently necessary in order to build optimized wasm files that can be deployed from a CasperLabs Node.

## Using

To deploy a compiled contract to a CasperLabs node, please see the CasperLabs [Developer Documentation](https://github.com/CasperLabs/CasperLabs/blob/dev/docs/CONTRACTS.md).
