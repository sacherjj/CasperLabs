## Guide to retrieve information from the CasperLabs platform

CasperLabs Node provides the [gRPC](https://grpc.io) and [GraphQL](https://graphql.org) APIs to retrieve information from the platform.

## gRPC
By default CasperLabs Node provides gRPC API [/protobuf/io/casperlabs/casper/protocol/CasperMessage.proto](/protobuf/io/casperlabs/casper/protocol/CasperMessage.proto) on the 40401 port, see for `grpc.port-external` in the [/node/src/main/resources/default-configuration.toml](/node/src/main/resources/default-configuration.toml).

CasperLabs provides the Scala and Python CLI tools to access the gRPC API. Python CLI tool also can be used as a library and published to the [PyPi.org](https://pypi.org) Python packages repository.

### Scala
Checkout our public repository where Scala client is published [http://repo.casperlabs.io/casperlabs/repo/](http://repo.casperlabs.io/casperlabs/repo/) and the Docker image: [https://hub.docker.com/r/casperlabs/client](https://hub.docker.com/r/casperlabs/client).

Also, it can be built locally, see [DEVELOPER.md#building-nodes-client](/DEVELOPER.md#building-nodes-client) for more information.

Print the help message (assuming if you decided to built it locally): `./client/target/universal/stage/bin/casperlabs-client --help`.

It will show all commands and their options.

### Python
See the doc at [/integration-testing/client/CasperClient/README.md](/integration-testing/client/CasperClient/README.md)

## GraphQL
CasperLabs Node provides a GraphQL API served on `/graphql` path and port 40403, by default (specified by `server.http-port` in the [default-configuration.toml](/node/src/main/resources/default-configuration.toml) config file).

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
