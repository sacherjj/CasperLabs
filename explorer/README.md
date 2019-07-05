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

Start a local docker network first:

```console
cd ../hack/docker
export CL_VERSION=test
make up node-0/up
cd -
```

The `server` component has a utility program to do the initial token transfer, let's build that first (not necessary if we already built everything with docker):

```console
cd server && npm run build && cd -
```

Run the transfer from the genesis account to our test faucet account.

```console
node ./server/dist/transfer.js \
  --host-url http://localhost:8401 \
  --transfer-contract-path contracts/target/wasm32-unknown-unknown/release/transfer.wasm \
  --from-private-key-path ../hack/docker/.casperlabs/genesis/system-account/account-private.pem \
  --from-public-key-path ../hack/docker/.casperlabs/genesis/system-account/account-public.pem \
  --to-public-key-path ./server/test.public.key \
  --amount 500000 \
  --nonce 1
```

If successful, it should print something like this:

```
Deploying 7401ecbe8b2c4e4de2c1e6422fddcfd1ae9d128058e2e6dba97ba62fc51db734 to http://localhost:8401
Done.
```

You can also confirm it in the node's logs in `hack/docker`:
```console
$ docker logs --tail 1 node-0
18:13:45.264 [grpc-default-executor-2] INFO  i.c.casper.MultiParentCasperImpl - Received Deploy 7401ecbe8b2c4e4de2c1e6422fddcfd1ae9d128058e2e6dba97ba62fc51db734 (f78786150599b50a1353476f5e2f12cd13c214e512096741c48e7ec63639af56 / 1)
```

The auto-propose feature is by default not enabled in the `hack/docker` setup,
so you have to manually trigger proposal.

```console
./client.sh node-0 propose
```

It should successfully print the block the deploy is included in:

```
Response: Success! Block 1a0974a728... created and added.
```

Following this we can check the status of our deploy:

```console
./client.sh node-0 show-deploy 7401ecbe8b2c4e4de2c1e6422fddcfd1ae9d128058e2e6dba97ba62fc51db734
```

The result of the processing and the fault tolerance can be found in the output:

```
deploy {
  deploy_hash: "7401ecbe8b2c4e4de2c1e6422fddcfd1ae9d128058e2e6dba97ba62fc51db734"
  header {
    account_public_key: "f78786150599b50a1353476f5e2f12cd13c214e512096741c48e7ec63639af56"
    nonce: 1
    timestamp: 1562350425051
    gas_price: 0
    body_hash: "1ba3a8335e68cbfd461865876bccc9225c560db9045d862ad16d26e3bbbe0a87"
  }
  ...
}
processing_results {
  block_info {
    summary {
      block_hash: "1a0974a72882549122370aeaf36a9a7e8cc782538c932d8a935903346e73afcc"
      ...
    }
    status {
      fault_tolerance: -1.0
      stats {
        block_size_bytes: 957590
        deploy_error_count: 1
      }
    }
  }
  cost: 48
  is_error: true
  error_message: "BytesRepr(EarlyEndOfStream)"
}
```

If the deploy was successfully executed we can check the balance of our account as follows:

```console
./client.sh node-0 query-state \
    -t address \
    -k f78786150599b50a1353476f5e2f12cd13c214e512096741c48e7ec63639af56 \
    -p "" \
    -b 1a0974a72882549122370aeaf36a9a7e8cc782538c932d8a935903346e73afcc
```
