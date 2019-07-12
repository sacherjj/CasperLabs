## Guide to retrieve information from the CasperLabs platform

CasperLabs Node provides the [gRPC](https://grpc.io) and [GraphQL](https://graphql.org) APIs to retrieve information from the platform.

## gRPC
By default CasperLabs Node provides gRPC API [/protobuf/io/casperlabs/node/api/casper.proto](/protobuf/io/casperlabs/node/api/casper.proto) on the 40401 port, see for `grpc.port-external` in the [/node/src/main/resources/default-configuration.toml](/node/src/main/resources/default-configuration.toml).

CasperLabs provides the Scala and Python CLI tools to access the gRPC API. Python CLI tool also can be used as a library and published to the [PyPi.org](https://pypi.org) Python packages repository.

### Scala
The Scala client is published to our [public repository](http://repo.casperlabs.io/casperlabs/repo/) as well as [Docker Hub](https://hub.docker.com/r/casperlabs/client).

Also, it can be built locally, see the [Developer Guide](/DEVELOPER.md#building-nodes-client) for more information.

Print the help message (assuming if you decided to built it locally): `./client/target/universal/stage/bin/casperlabs-client --help`.

It will show all commands and their options.

#### The State Query API

##### Background
Fundamentally, the CasperLabs system is a distributed key-value store
with particular rules around how that store can be updated. We call a
snapshot of the key-value store at a particular point in time the
"global state". The global state contains all the data which is
important to users of the system (e.g. token balances, smart contract
logic, etc.). Keys for the key-value store come in three forms:
`Address`, `Hash`, and `URef`. Each key type serves a different
purpose. `Address`-type keys have accounts as the associated value
(more details on accounts will follow), `Hash`-type keys store "smart
contracts", and `URef`-type keys store any data which can be
represented in the [CasperLabs
ABI](https://techspec.casperlabs.io/technical-details/block-storage/global-state#abi)
(except accounts). An account is a cryptographically-secured
entry-point into the system. All changes to the global state must be
tied to an account.

##### Purpose

This API is used to query the value under a key in a particular
version of the global state. For example, this can be used to track an
important smart contract state variable or account token balance from
block-to-block.

###### API

The `GetBlockState` gRPC method (`query-state` on the CLI client) of the CasperLabs node accepts a message
with four parameters:

- Block hash
  - Determines the particular global state to query. The post state of
    the block with the given hash is used.
  - The hash is presented as a base-16 encoded string.
- Key type
  - Specified the type of key from which to start the query. Allowed
    values are `ADDRESS`, `HASH` and `UREF`, same as the types of keys
    described above.
  - The key type is passed as lowercased string on the CLI.
- Key bytes
  - The bytes which are used to identify the particular key. `URef`
    and `Hash` type keys use 32-byte identifiers, while `Address` uses
    only 20 bytes (these bytes are commonly known as the account
    address).
  - The bytes are presented as a base16-encoded string.
- Path
  - The sequence of human-readable names which can be used to reach
    the desired key to query. This makes use of the human-readable
    name associations accounts and contracts have for keys.
  - The path is presented as a '/'-separated string of identifiers on the CLI.

#### Visualising DAG state

In the root of the node, run (assuming the Scala client built locally, see the [Developer Guide](/DEVELOPER.md#building-nodes-client) for more information.):

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port 40401 vdag --depth 10 --out test.png
```

The output will be saved into the `test.png` file.

It's also possible subscribing to DAG changes and view them in the realtime

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port 40401 vdag --depth 10 --out test.png --stream=multiple-outputs
```

The outputs will be saved into the files `test_0.png`, `test_1.png`, etc.

For more information and possible output image formats check the help message

```
./client/target/universal/stage/bin/casperlabs-client vdag --help
```

### Python
See the doc at [/integration-testing/client/CasperClient/README.md](/integration-testing/client/CasperClient/README.md)

## GraphQL
CasperLabs Node provides a GraphQL API served on `/graphql` path and port 40403, by default (specified by `server.http-port` in the [default-configuration.toml](/node/src/main/resources/default-configuration.toml) config file).

To explore the API, read the docs and signatures, open the GraphQL Playground (https://github.com/prisma/graphql-playground) on `http://<node's IP address or hostname>:40403/graphql` in a web browser.

Currently, GraphQL allows only read-only queries without mutations.

To explore possible queries start typing `query` or `subscription` to get an auto-completion list, or click the [DOCS] or [SCHEMA] button on the right hand side of the screen.

Example of possible query:
```graphql
query {
  deploy(deployHashBase16:"<PUT DEPLOY HASH HERE>") {
    processingResults {
      cost
      isError
      errorMessage
    }
  }
}
``` 
