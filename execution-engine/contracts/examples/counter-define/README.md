# Deploy Counter

Implementation of URef-based counter.

The deployement of this session code, creates new named key `counter`, that points to the deployed smart contract. The `counter` smart contract will have one named key called `count`, that points to the actual integer value, that is the counter.

The `counter` smart contract exposes two methods:
- `inc`, that increments the `count` value by one;
- `get`, that returns the value of `count`.

## Build

Build the `wasm` file using `make` in the `execution-engine` directory.
```
$ make build-contract/counter-define
```

## Deploy

Deploy the counter smart contract.
```
$ casperlabs-client --host $HOST deploy \
    --private-key $PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session $COUNTER_DEFINE_WASM
```

Increment the counter.
```
$ casperlabs-client --host $HOST deploy \
    --private-key $PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session-name counter_inc
```

## Check counter's value

Query global state to check counter's value.
```
$ casperlabs-client --host $HOST query-state \
    --block-hash $BLOCK_HASH \
    --type address \
    --key $PUBLIC_KEY \
    --path "counter/count"
```