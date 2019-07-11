## Guide to retrieve information from the CasperLabs platform

CasperLabs Node provides the [gRPC](https://grpc.io) and [GraphQL](https://graphql.org) APIs to retrieve information from the platform.

## gRPC
By default CasperLabs Node provides gRPC API [/protobuf/io/casperlabs/casper/protocol/CasperMessage.proto](/protobuf/io/casperlabs/casper/protocol/CasperMessage.proto) on the 40401 port, see for `grpc.port-external` in the [/node/src/main/resources/default-configuration.toml](/node/src/main/resources/default-configuration.toml).

CasperLabs provides the Scala and Python CLI tools to access the gRPC API. Python CLI tool also can be used as a library and published to the [PyPi.org](https://pypi.org) Python packages repository.

### Scala
Checkout our public repository where Scala client is published [http://repo.casperlabs.io/casperlabs/repo/](http://repo.casperlabs.io/casperlabs/repo/) and the Docker image: [https://hub.docker.com/r/casperlabs/client](https://hub.docker.com/r/casperlabs/client).

Also, it can be built locally, see [DEVELOPER.md#building-nodes-client](/DEVELOPER.md#building-nodes-client) for more information.

Print the help message (assuming if you decided to built it locally):
```bash
$ ./client/target/universal/stage/bin/casperlabs-client --help
CasperLabs Client 0.5.0
  -h, --host  <arg>            Hostname or IP of node on which the gRPC service
                               is running.
  -n, --node-id  <arg>         Node ID (i.e. the Keccak256 hash of the public
                               key the node uses for TLS) in case secure
                               communication is needed.
  -p, --port  <arg>            Port used for exte
  rnal gRPC API.
      --port-internal  <arg>   Port used for internal gRPC API.
      --help                   Show help message
  -v, --version                Show version of this program

Subcommand: deploy - Deploy a smart contract source file to Casper on an existing running node. The deploy will be packaged and sent as a block to the network depending on the configuration of the Casper instance.
  -f, --from  <arg>          The public key of the account which is the context
                             of this deployment, base16 encoded.
  -g, --gas-limit  <arg>     [Deprecated] The amount of gas to use for the
                             transaction (unused gas is refunded). Must be
                             positive integer.
      --gas-price  <arg>     The price of gas for this transaction in units
                             dust/gas. Must be positive integer.
  -n, --nonce  <arg>         This allows you to overwrite your own pending
                             transactions that use the same nonce.
  -p, --payment  <arg>       Path to the file with payment code
      --private-key  <arg>   Path to the file with account private key (Ed25519)
      --public-key  <arg>    Path to the file with account public key (Ed25519)
  -s, --session  <arg>       Path to the file with session code
  -h, --help                 Show help message
Subcommand: propose - Force a node to propose a block based on its accumulated deploys.
  -h, --help   Show help message
Subcommand: show-block - View properties of a block known by Casper on an existing running node.
  -h, --help   Show help message

 trailing arguments:
  hash (required)   Value of the block hash, base16 encoded.
Subcommand: show-deploys - View deploys included in a block.
  -h, --help   Show help message

 trailing arguments:
  hash (required)   Value of the block hash, base16 encoded.
Subcommand: show-deploy - View properties of a deploy known by Casper on an existing running node.
  -h, --help   Show help message

 trailing arguments:
  hash (required)   Value of the deploy hash, base16 encoded.
Subcommand: show-blocks - View list of blocks in the current Casper view on an existing running node.
  -d, --depth  <arg>   lists blocks to the given depth in terms of block height
  -h, --help           Show help message
Subcommand: vdag - DAG in DOT format
  -d, --depth  <arg>               depth in terms of block height
  -o, --out  <arg>                 output image filename, outputs to stdout if
                                   not specified, must ends with one of the png,
                                   svg, svg_standalone, xdot, plain, plain_ext,
                                   ps, ps2, json, json0
  -s, --show-justification-lines   if justification lines should be shown
      --stream  <arg>              subscribe to changes, '--out' has to
                                   specified, valid values are 'single-output',
                                   'multiple-outputs'
  -h, --help                       Show help message
Subcommand: query-state - Query a value in the global state.
  -b, --block-hash  <arg>   Hash of the block to query the state of
  -k, --key  <arg>          Base16 encoding of the base key.
  -p, --path  <arg>         Path to the value to query. Must be of the form
                            'key1/key2/.../keyn'
  -t, --type  <arg>         Type of base key. Must be one of 'hash', 'uref',
                            'address'
  -h, --help                Show help message
```

Commands for querying the platform are `show-block`, `show-deploy`, `show-blocks`, `vdag` and `query-state`.

To get help for a certain command invoke it with the `--help` option as follows:
```bash
$ ./client/target/universal/stage/bin/casperlabs-client query-state --help
  -b, --block-hash  <arg>   Hash of the block to query the state of
  -k, --key  <arg>          Base16 encoding of the base key.
  -p, --path  <arg>         Path to the value to query. Must be of the form
                            'key1/key2/.../keyn'
  -t, --type  <arg>         Type of base key. Must be one of 'hash', 'uref',
                            'address'
  -h, --help                Show help message
```

### Python
See the doc at [/integration-testing/client/CasperClient/README.md](/integration-testing/client/CasperClient/README.md)

## GraphQL
CasperLabs Node provides a GraphQL API served on `/graphql` path and 40403 port by default, , see for `server.http-port` in the [/node/src/main/resources/default-configuration.toml](/node/src/main/resources/default-configuration.toml).

To explore the API, read the docs and signatures, open the GraphQL Playground (https://github.com/prisma/graphql-playground) on `http://<node's IP address or hostname>:40403/graphql` in a web browser.

Currently, GraphQL allows only read-only queries without mutations.

To explore possible queries start typing `query` or `subscription` to get an auto-completion list.

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