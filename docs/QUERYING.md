## Querying the CasperLabs platform

CasperLabs Node provides the [gRPC](https://grpc.io) and [GraphQL](https://graphql.org) APIs to retrieve information from the platform.

## gRPC
By default CasperLabs Node provides gRPC API [/protobuf/io/casperlabs/node/api/casper.proto](/protobuf/io/casperlabs/node/api/casper.proto) on the 40401 port, see for `grpc.port-external` in the [/node/src/main/resources/default-configuration.toml](/node/src/main/resources/default-configuration.toml).

### Client
#### Python CLI

CasperLabs provides the [Python](https://pypi.org/project/casperlabs-client/) CLI tools to access the gRPC API. 
Python [casperlabs-client](https://pypi.org/project/casperlabs-client/) module is a Python 3.6+ only library. It can be installed with `pip install casperlabs-client`, see [casperlabs-client](https://pypi.org/project/casperlabs-client/) package documentation on [PyPi](https://pypi.org/).

The package also contains a CLI tool `casperlabs_client`. Note, the Python CLI has `'_'` (underscore) in its name. 

Run `casperlabs_client --help` to see available commands and connection options. `casperlabs_client <command> --help` shows detailed information on options of a specific command, for example: `casperlabs_client deploy --help`.

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
ABI](https://techspec.casperlabs.io/en/latest/implementation/appendix.html#b-serialization-format)
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
