# CasperLabs Python Client API library and command line tool

CasperLabs Python client is a library that can be used to issue requests
to CasperLabs node's gRPC Deploy API. 

It also provides command line interface with syntax compatible with the Scala client.

## Installation

You can install the `casperlabs-client` library with `pip install casperlabs-client` and then
you can start using it in your programs.

## Typical usage of the API

Instantiate `CasperClient` and issue a requests.
`CasperClient` constructor accepts hostname or IP of node on which gRPC service is running on 
and the service's port.

Both parameters of the CasperClient constructor are optional.
By default the client will communicate with node running on
localhost and listening on port 40401. 

```python
from casper_client import CasperClient

client = CasperClient('node1', 40402)

response = client.deploy(from_=b"00000000000000000000",
                         gas_limit=100000000,
                         gas_price=1,
                         session="session.wasm",
                         payment="payment.wasm")
if response.success:
    print ('Deployed OK!')

```

Methods of the `CasperClient` class define the Python API and correspond to requests defined in 
[CasperMessage.proto](../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto).


User can instantiate several instances of `CasperClient` and configure them to
communicate with nodes running on different machines:

```python
from casper_client import CasperClient

client1 = CasperClient('node1host', 40401)
client2 = CasperClient('node2host', 40401)
```

### Return values

Return values of the API functions defined in the `CasperClient` are generally deserialized gRPC reponse objects 
of the corresponding requests defined in the node's Deploy service, see 
[CasperMessage.proto](../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto).

Response to requests like `showBlocks` or `showMainChain` is a stream of objects (blocks).
Corresponding Python API functions return generator objects:

```python
for block in client.showBlocks(depth=10):
    print (block.blockHash)
```

### Error handling

Some requests' response objects (see their definitions in 
[CasperMessage.proto](../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto)
) have fields indicating success.

In general, the API calls should not throw exceptions. 
In case of an internal error, for example node's gRPC service not catching a Scala exception
(e.g. [NODE-451](https://casperlabs.atlassian.net/browse/NODE-451)),
the Python client will throw `InternalError`. 
`InternalError` is the only exception that user code can expect to be thrown by the API.

## Command Line Interface

Apart from being a library `casper_client.py` provides also a command line interface.

When executed with no parameters it will print to the console short usage summary, 
running it with `--help` will print more details. 

```bash
$ ./casper_client.py --help
usage: casper_client.py [--help] [-h HOST] [-p PORT]
                        {deploy,propose,show-block,show-blocks,vdag,query-state,show-main-chain,find-block-with-deploy}
                        ...

positional arguments:
  {deploy,propose,show-block,show-blocks,vdag,query-state,show-main-chain,find-block-with-deploy}
                        Choose a request
    deploy              Deploy a smart contract source file to Casper on an
                        existing running node. The deploy will be packaged and
                        sent as a block to the network depending on the
                        configuration of the Casper instance
    propose             Force a node to propose a block based on its
                        accumulated deploys.
    show-block          View properties of a block known by Casper on an
                        existing running node. Output includes: parent hashes,
                        storage contents of the tuplespace.
    show-blocks         View list of blocks in the current Casper view on an
                        existing running node.
    vdag                DAG in DOT format
    query-state         Query a value in the global state.
    show-main-chain     Show main chain
    find-block-with-deploy
                        Find block with deploy.

optional arguments:
  --help                show this help message and exit
  -h HOST, --host HOST  Hostname or IP of node on which gRPC service is
                        running.
  -p PORT, --port PORT  Port used for external gRPC API.

```

To get detailed description of syntax of a chosen request (command), run it with `--help`:

```bash
$ ./casper_client.py deploy --help
usage: casper_client.py deploy [-h] -f FROM -g GAS_LIMIT --gas-price GAS_PRICE
                               [-n NONCE] -p PAYMENT -s SESSION

optional arguments:
  -h, --help            show this help message and exit
  -f FROM, --from FROM  Purse address that will be used to pay for the
                        deployment.
  -g GAS_LIMIT, --gas-limit GAS_LIMIT
                        The amount of gas to use for the transaction (unused
                        gas is refunded). Must be positive integer.
  --gas-price GAS_PRICE
                        The price of gas for this transaction in units
                        dust/gas. Must be positive integer.
  -n NONCE, --nonce NONCE
                        This allows you to overwrite your own pending
                        transactions that use the same nonce.
  -p PAYMENT, --payment PAYMENT
                        Path to the file with payment code
  -s SESSION, --session SESSION
                        Path to the file with session code

```

```bash
$ ./casper_client.py deploy --from 3030303030303030303030303030303030303030303030303030303030303030 --gas-limit 100000000 --gas-price 1 --session session.wasm --payment payment.wasm
Success!
```

CLI indicates successful execution of a request by returning `0`.
In case of an error the tool will return a positive error code.
This can useful in shell scripting.

See also [Usage of the CasperLabs system](https://github.com/CasperLabs/CasperLabs/blob/dev/USAGE.md).


## Development

Modules `CasperMessage_pb2.py`, `CasperMessage_pb2_grpc.py`, `scalapb/scalapb_pb2_grpc.py` and `scalapb/scalapb_pb2.py`
are generated from gRPC service definitions with `protoc`. They can be regenerated by running `./run_codegen.py`.
