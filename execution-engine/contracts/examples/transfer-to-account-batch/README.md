# Transfer to multiple accounts

This contract shows an example of passing complex arguments (in this case a
collection of tuples consisting of `PublicKey` and `U512`) to session code. In
this example, our use case is batching together many transfers into a single
deploy (hence the reason for needing the list of tuples).

## Build

Build the `wasm` file using `make` in the `execution-engine` directory.

```
$ make build-contract-rs/transfer-to-account-batch
```

## Deploy

Deploy the batched transfer smart contract. We assume that this command is being
execute in the `execution-engine` directory.

```shell
$ export TRANSFER_BATCH_WASM=target/wasm32-unknown-unknown/release/transfer_to_account_batch.wasm

$ casperlabs-client --host $HOST deploy \
    --private-key $PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session $TRANSFER_BATCH_WASM
```

Note: the value of `HOST` depends on if you are deploying to a local node
(`export HOST=localhost`), or a CasperLabs test network (e.g. `export HOST=deploy.casperlabs.io`).
The value of `PRIVATE_KEY_PATH` is the path to the private key of your account on
the system (if you are not sure how to make an account see
[our documentation](https://github.com/CasperLabs/CasperLabs/blob/v0.14.0/docs/CONTRACTS.md)).

## Perform a transfer

Call the transfer contract using the name it was installed under in the previous
deploy. Notice that here we are using `xargs` to allow passing the contents of a
file as the arguments of our deploy (in the file we specify the public keys of
the accounts to transfer to and the amounts to send).

```shell
$ export ARGS=contracts/examples/transfer-to-account-batch/transfer_batch_args.json

$ xargs --null --arg-file $ARGS \
    casperlabs-client --host $HOST deploy \
    --private-key $PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session-name transfer_batch \
    --session-args
```
## Query the balances to verify the transfer

We can confirm all the transfers happened by querying the balances of the target
accounts.

```shell
$ casperlabs-client --host $HOST balance \
  --address $ADDRESS \
  --block-hash $BLOCK_HASH
```
The value of `ADDRESS` depends on what accounts are listed in
`transfer_batch_args.json`. If you did not modify the file, then it can be any
one of

- `7a7b66a8db460cc1ab82b5c35bb8fb871a98758c2d13a1c728c76b175c80515c`
- `d638e05818884aef85ea6221ea35966bdeb438b80a4bd3c7fd0a859e3f99c828`
- `bc7819fb418346da96ac0bf82354d4446367ab307cb34c6b46e35e03bef9ee2a`

The value of `BLOCK_HASH` must be the hash of the block which included the
transfer call above. Depending on the network you are deploying from, you may be
able to determine this from [Clarity](https://clarity.casperlabs.io/#/deploys),
or the command line

```shell
$ casperlabs-client --host $HOST show-deploy $DEPLOY_HASH | grep block_hash -m 1
```

where `DEPLOY_HASH` comes from the output of the `deploy` command in the previous section.
