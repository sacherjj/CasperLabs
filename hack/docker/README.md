# Node network simulation

The idea is to create many nodes with commands like `make node-0/up`, bring them down with `make node-1/down` etc. Each will have the same configuration as `template/Dockerfile`. The containers would all be on the same network.

To deploy we need to use `docker run --network casperlabs casperlabs/client` and pass it the WASM files. `client.sh` provides is a convenient wrapper for interacting with the network. Run `./client.sh node-0 --help` to see what it can do.

## Build docker images

Run `make docker-build-all` in the main project directory to prepare the images. It will rebuild anything where the source code changed since last time.

## Build contract-examples

See instructions [here](https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/contracts/examples/README.md).

## Required: docker-compose

`docker-compose` is used to bring up the nodes on the network. Please verify that [docker-compose is installed](https://docs.docker.com/compose/install/) prior to continuing.

## Required: OpenSSL 1.1

`openssl` is used to generate keys and certificates. Please verify that [the latest OpenSSL 1.1 version is installed](https://github.com/openssl/openssl). You can also [follow these steps](https://github.com/CasperLabs/CasperLabs/blob/dev/docs/KEYS.md)

## Required: SHA3SUM

`keccak-256sum` is used to generate node TLS certificate. Please verify that [sha3sum is installed](https://github.com/maandree/sha3sum).


## Set up a network

We will create multiple nodes in docker with names such as `node-0`, `node-1` etc. Each will have a corresponding container running the Execution Engine.

The setup process will establish validator keys in `.casperlabs/node-*` and bonds in `.casperlabs/genesis` by executing [docker-gen-keys.sh](/hack/key-management/docker-gen-keys.sh). By default 3 nodes' keys are created but you can generate more by setting the `CL_CASPER_NUM_VALIDATORS` variable.

Up to 10 nodes can be created due to the way ports are being exposed on the host: you can deploy to `node-0` on 40401, `node-1` on 40411, `node-2` on 40421, and so on.

If you plan to do lots of deploys it can help to enable auto-proposing, so you don't have to issue `propose` commands after each deploy. To do so just run `export CL_CASPER_AUTO_PROPOSE_ENABLED=true` prior to running the `make` commands.

`node-0` will be the bootstrap node that all subsequent nodes connect to, so create that first.

Run the following command to establish its data directory and see the values docker-compose will use:

```console
make node-0
```

Or, just run the following command to bring up the node in a Docker container straight away:

```console
make node-0/up
```

You can check that the node is running with the following commands:

```console
docker ps
```

and follow ("tail") the nodes logs by running:

```console
docker logs -f node-0
```

Once the bootstrap node is up, run similar commands to bring up other nodes:

```console
make node-1/up node-2/up
```

After connection is complete, all node logs will show `Peers: 2`.

## Cleanup
To cleanup the network stopping and removing all containers run the command `make clean`.

## Signing Deploys

The Makefile will have automatically generated some keys to test with under the `keys` directory: there will be the `faucet-account` for Clarity, and an `account-0` .. `account-$n` file up to the number of nodes. Apart from the faucet, these don't have any initial funds, but you can transfer to them from the Faucet.

## Deploy some WASM code

To deploy you'll need a client. You have multiple options:
* Install the [Scala client](../../docs/INSTALL.md),
* Install the [Python client](../../integration-testing/client/CasperLabsClient)
* If you built the project, you can set an alias in the console: `alias casperlabs-client=$PWD/../../client/target/universal/stage/bin/casperlabs-client`
* You can use the dockerized client as well, if you mount directories with the Wasm files, the keys, and use the `--network casperlabs` option.

Assuming that you compiled the [contract examples](https://github.com/CasperLabs/CasperLabs/tree/dev/execution-engine/contracts/examples) you can deploy them by running the following:

```console
ACCOUNT_ID="$(cat keys/faucet-account/account-id-hex)"
casperlabs-client --host localhost --port 40401 deploy \
     --gas-price 1 \
     --from "$ACCOUNT_ID" \
     --session $PWD/../../execution-engine/target/wasm32-unknown-unknown/release/hello_name_define.wasm \
     --payment $PWD/../../execution-engine/target/wasm32-unknown-unknown/release/standard_payment.wasm \
     --public-key keys/faucet-account/account-public.pem \
     --private-key keys/faucet-account/account-private.pem
```

As you may notice we make use of the `faucet-account` for deploys signing. This is just a test account that has some initial balance to play with and fund other accounts, it has no other role.

After a successful deploy, you should see the following response:

```
Success!
```

At the moment you have to trigger block proposal by invoking the following command:

```console
casperlabs-client --host localhost --port-internal 40402 propose
```

After a successful deploy, the response will contain the block ID:

```
Response: Success! Block f876efed8d... created and added.
```

If you check the log output, each node should get the block and provide some feedback about the execution as well.

### TLS

If the nodes have the `CL_GRPC_USE_TLS` set to `true` they'll expect the client to use an encrypted gRPC connection.
What you have to do is get the node ID from the files that were generated for the node and pass it as a CLI option,
for example:

```bash
# Get the node-id for TLS.
NODE=0
NODE_ID=$(cat $DIR/.casperlabs/node-$NODE/node-id)
casperlabs-client --host localhost --port 404${NODE}1 --node-id $NODE_ID show-blocks --depth 10
```

## Monitoring

### Prometheus

Running `make up` will install some common containers in the network, for example a [Prometheus](https://prometheus.io) server which will be available at http://localhost:9090. The list of [targets](http://localhost:9090/targets) will be updated every time we create or destroy nodes.

### Grafana

To see some of the metrics in [Grafana](https://grafana.com/) go to http://localhost:3000 and log in with the credentials "admin/admin" and skip prompt to change password.  This is just a local instance of Grafana, so only accessible from localhost.

The Block Gossiping dashboard will display charts that show communication overhead.  Click on the dashboards (2x2 blocks) icon on the left if you don't see the Block Gossiping dashboard link.

Note that you'll need to run `docker login` with your DockerHub username and password to be able to pull 3rd party images.

## Clarity

Running `make up` will also start a local instance of Clarity at https://localhost:8443 where you can use the Faucet, visualize the DAG with the Explorer. The UI will connect to `node-0`, so that container needs to be running already.

## Visualizing the DAG

You can save snapshots of the DAG as it evolves, like a slide show, by starting the the client in a mode which saves a new image every time it changes. You have to give it a target directory (which will be available as `/data` in the container) and start it, then perform your deploy and propose actions in a different terminal. The `images` directory in the example below will accumulate files like `sample_1.png`, `sample_2.png`, etc.

```console
./client.sh node-0 vdag $PWD/images --show-justification-lines --depth 25 \
    --out /data/sample.png --stream multiple-outputs
```

As you make blocks, you should see feedback about a new image written to the output. You can stop the client using `Ctrl+C`:

```
Wrote /data/sample_0.png
Wrote /data/sample_1.png
^C
```

If you check the contents of the `images` directory you'll see that they are still there:

```console
ls images
```

You'll see the images right under the output directory we specified as the 2nd argument when we started the client:

```
sample_0.png  sample_1.png
```

Unfortunately the docker container runs with a different user ID than the one on the host and the will set the ownership of these images so that they can only be removed with elevated privileges. Normally you'd install the client directly on your machine and not have this issue, connecting to the node through an open port rather than through a docker container. We're only using the client through the container so we don't have to map to different ports on the host for each node we want to deploy to.

```console
sudo rm -rf images
```

On Debian/Ubuntu you can also run `sudo apt-get install graphviz` and visualize the DAG like so:

```console
./client.sh node-0 vdag --show-justification-lines --depth 25 \
    | dot -Tpng -o /tmp/cl-dag.png \
    && xdg-open /tmp/cl-dag.png
```

Alternatively you can even use the browser:

```console
google-chrome --new-window \
    $(python -c "import urllib; print 'https://dreampuf.github.io/GraphvizOnline/#' + urllib.quote('''$(./client.sh node-0 vdag --show-justification-lines --depth 25)''')")
```

## Execute GraphQL Queries

The node includes a GraphQL console which you can use to explore the schema and build queries with the help of auto-completion. To access it, first make sure the top level docker containers and the bootstrap container are started: `make up node-0/up`. Once that's done you can point your browser at http://localhost:40403/graphql

See what's exposed by clicking the _DOCS_ and _SCHEMA_ buttons on the right-hand side of the screen. To run a query, start typing "query" or "subscription" into the left-hand pane and see what the code completion offers up. You can keep the _DOCS_ open on the right hand side to see what's available; close it when you finished your query and press the "play" button in the middle to see the response.

For example you can use the following query to see the top 5 ranks of the DAG:
```json
query {
  dagSlice(depth: 5) {
    blockHash
    parentHashes
    deployCount
  }
}
```

You can use the _COPY CURL_ button to see what an equivalent pure HTTP/JSON command would be.

## Network Effects

You can slow the network down a bit by running `make delay` or `make slow` in one terminal while issuing deploys in another one. You should see that now it takes longer for nodes to catch up after a block is created; even just sending the deploy is going to take a bit more time.

Another way to introduce a network partition is to for example create two networks, put the bootstrap node in both of them, but the rest of the nodes in just one, half here, half there. Then either slow down just the bootstrap node by introducing packet loss using the [netem](https://alexei-led.github.io/post/pumba_docker_netem/) tool, or by running `docker exec -it --privileged node-0 sh` and using `iptables`:

```console
# Drop traffic going to the intra-node gRPC port:
iptables --append INPUT --protocol tcp --destination-port 40400 --jump DROP
# Later delete the rule to restore traffic:
iptables --delete INPUT --protocol tcp --destination-port 40400 --jump DROP
```

The effect should be that because the bootstrap node never sees any gossiping the two halves of the network can build the chain independently for a while. When the bootstrap node is restored and sees a new block, it will try to catch up with the missing parts of the DAG and forward it to its peers, reconnecting the two halves.

You can also have a look at [using tc to slow down a specific port](https://stackoverflow.com/questions/10694730/in-linux-simulate-slow-traffic-incoming-traffic-to-port-e-g-54000). The benefit of using `iptables` and `tc` on individual port rather than the node level like `make delay` is that the deployment and metrics endpoints can stay unaffected.

_NOTE_: For the techniques that use `iptables` and `tc` to work you'll need to run the `test` version of the node image which you should have if you built the images yourself with `make docker-build-all` in the top directory, and subsequently set `export CL_VERSION=test` before creating the containers. You can set the the environment variable later as well, then run run `make node-0/up` again and see `docker-compose` recreating the containers with the new version.

## Shut down the network

You can destory individual nodes:

```console
make node-1/down
```

You can tear everything down by running:

```console
make clean
```

## Long Running Test (LRT)

You can run the similar setup as we use at SRE team to test how nodes perform over time.

[The script to run LRT](/hack/docker/scripts/lrt.sh).
