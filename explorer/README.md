# Casper Explorer

The purpose of the explorer is help users interact with the CasperLabs blockchain:
* Sign up to participate in devnet
* Create accounts (public/private key pairs)
* Ask the faucet for tokens on devnet
* Explore the block DAG

## Build

You can use `npm` in the `ui` and the `server` directories to build and interactively develop the components.
To package the whole thing into a docker image, run `make docker-build/explorer` in the project root directory.

## Test

To test the faucet we need the node running and ready to accept deploys.
We also have to fund it with initial tokens and send one deploy in the name of the genesis account which will transfer funds to the faucet.

We can use the `contracts/transfer` to donate the initial amount of funds to the faucet. Contracts will be built together with the docker image, but you can build them separately by running `make .make/explorer/contracts` in the top level directory.

Start a local docker network first.

```console
cd ../hack/docker
make up node-0/up
```
