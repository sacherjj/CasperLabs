# CasperLabs Python Client API library and command line tool

[CasperLabs](https://casperlabs.io/) Python client is a library that can be used to 
interact with a CasperLabs node via its gRPC API. 

## Installation

`casperlabs-client` is a Python 3.6 module, it does not support Python 2.7.
You can install it with 

```
pip install casperlabs-client
```

or, if you have both Python 2 and Python 3 installed:

```
pip3 install casperlabs-client
```

## Getting started 

After installing `casperlabs-client` you can start interacting with
[CasperLabs devnet](https://explorer.casperlabs.io).


```python
import casper_client
client = casper_client.CasperClient('deploy.casperlabs.io', 40401)
blockInfo = next(client.showBlocks(1, full_view=False))
for bond in blockInfo.summary.header.state.bonds:
    print(f'{bond.validator_public_key.hex()}: {bond.stake}')
```

When executed the script should print a list of bonded validators' public keys
and their stake:

```
89e744783c2d70902a5f2ef78e82e1f44102b5eb08ca6234241d95e50f615a6b: 5000000000
1f66ea6321a48a935f66e97d4f7e60ee2d7fc9ccc62dfbe310f33b4839fc62eb: 8000000000
569b41d574c46390212d698660b5326269ddb0a761d1294258897ac717b4958b: 4000000000
d286526663ca3766c80781543a148c635f2388bfe128981c3e4ac69cea88dc35: 3000000000
```

## Deploying smart contracts

To deploy a smart contract to CasperLabs devnet you have to first:

1. Create an account using [CasperLabs Explorer](https://explorer.casperlabs.io/#/)
and transfer (free) tokens to the account from the faucet.

   An account address is a public key looking like
   ```
   f2cbd19d054bd2b2c06ea26714275271663a5e4503d5d059de159c3b60d81ab7
   ```

2. Compile a contract to the [WASM](https://webassembly.org) format,
see CasperLabs [contract examples](https://github.com/CasperLabs/contract-examples)
to see example contracts 
and instructions on 
[how to compile](https://github.com/CasperLabs/contract-examples/blob/master/README.md)
them.

To deploy a compiled contract from your account address:

```python
    response = client.deploy(from_addr="f2cbd19d054bd2b2c06ea26714275271663a5e4503d5d059de159c3b60d81ab7",
                             gas_limit=1000000,
                             gas_price=1,
                             payment="helloname.wasm",
                             session="helloname.wasm",
                             nonce=1)
```

### Return values

Return values of the API functions defined in the `CasperClient` are generally deserialized gRPC response objects 
of the corresponding requests defined in the node's gRPC service, see 
[casper.proto](https://github.com/CasperLabs/CasperLabs/blob/master/protobuf/io/casperlabs/node/api/casper.proto).

Response to requests like `showBlocks` or `showDeploys` is a stream of objects.
Corresponding Python API functions return generator objects:

```python
for block in client.showBlocks(depth=10):
    print (block.blockHash)
```

### Error handling

Some requests' response objects (see their definitions in 
[casper.proto](https://github.com/CasperLabs/CasperLabs/blob/master/protobuf/io/casperlabs/node/api/casper.proto)
) have fields indicating success.

`InternalError` is the only exception that user code can expect to be thrown by the API.


### Learn more about CasperLabs blockchain
See [Usage of the CasperLabs system](https://github.com/CasperLabs/CasperLabs/blob/master/USAGE.md).
