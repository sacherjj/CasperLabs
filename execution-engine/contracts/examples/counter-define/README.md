# Deploy Counter

Implementation of URef-based counter.

## Compile

Build the `wasm` file using `make` in the `execution-engine` directory.
```
# make build-contract/counter-define
```

## Deploy

Deploy the counter smart contract.
```
casperlabs-client --host $HOST deploy \
    --private-key $PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session $WASM_FILE_PATH
```

## Check counter's value

Query global state to check counter's value.
```
casperlabs-client --host $HOST query-state \
    --block-hash $BLOCK_HASH \
    --type address \
    --key $PUBLIC_KEY \
    --path "counter/count"
```