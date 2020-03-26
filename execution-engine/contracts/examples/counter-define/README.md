# Deploy Counter

Implementation of URef-based counter.

The deployement of this session code, creates new named key `counter`, that points to the deployed smart contract. The `counter` smart contract will have one named key called `count`, that points to the actual integer value, that is the counter.

The `counter` smart contract exposes two methods:
- `inc`, that increments the `count` value by one;
- `get`, that returns the value of `count`.

## Build

Build the `wasm` file using `make` in the `execution-engine` directory.
```
$ make build-contract-rs/counter-define
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

## Use the counter from another account

First we need to know the locations of the contracts. We look up the account
they were deployed from and find the `"counter"` and `"counter_inc"` named keys.

```
$ casperlabs-client --host $HOST query-state \
    --block-hash $BLOCK_HASH \
    --key $PUBLIC_KEY \
    --type address \
    --path ""

account {
  ...
  named_keys {
    name: "counter"
    key {
      hash {
        hash: "<COUNTER>"
      }
    }
  }
  named_keys {
    name: "counter_inc"
    key {
      hash {
        hash: "<COUNTER_INC>"
      }
    }
  }
  ...
}
```

Then we call the `"counter_inc"` key, passing the `"counter"` key as an argument:

```
$ casperlabs-client --host $HOST deploy \
    --private-key $DIFFERENT_PRIVATE_KEY_PATH \
    --payment-amount 10000000 \
    --session-hash <COUNTER_INC>
    --session-args '[{"name" : "counter_key", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"hash": {"hash": "<COUNTER>"}}}}}]'
```

Or with the [Python client](https://pypi.org/project/casperlabs-client/):

```python
import casperlabs_client
from casperlabs_client.abi import ABI

# Variables depending on your setup
host = "<host-for-your-setup>"
block_hash = "<block-counter-define-included-in>"
counter_account = "<base16-public-key-of-account-deploying-counter-define>"
different_account_id = "<base16-public-key-of-other-account>"
different_account_pk = "<path-to-other-account-public-key>"
different_account_sk = "<path-to-other-account-private-key>"

# Create client instance
client = casperlabs_client.CasperLabsClient(host=host)

# Look up named keys for account which deployed `counter_define`
named_keys = client.queryState(
    key=counter_account,
    keyType="address",
    path="",
    blockHash=block_hash
).account.named_keys
# Get addresses of `counter` and `counter_inc` contracts
counter_address = next(filter(lambda x: x.name == "counter", named_keys)).key.hash.hash
counter_inc_address = next(filter(lambda x: x.name == "counter_inc", named_keys)).key.hash.hash

# Send deploy from other account to increment the counter
deploy_hash = client.deploy(
    from_addr=different_account_id,
    session_hash=counter_inc_address,
    session_args=[ABI.key_hash("counter_key", counter_address)],
    payment_amount=10000000,
    private_key=different_account_sk,
    public_key=different_account_pk
)
```
