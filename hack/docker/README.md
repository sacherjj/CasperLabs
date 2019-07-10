# Node network simulation

The idea is to create many nodes with commands like `make node-0/up`, bring them down with `make node-1/down` etc. Each will have the same configuration as `template/Dockerfile`. The containers would all be on the same network.

To deploy we need to use `docker run --network casperlabs casperlabs/client` and pass it the WASM files. `client.sh` provides is a convenient wrapper for interacting with the network. Run `./client.sh node-0 --help` to see what it can do.

## Build docker images

Run `make docker-build-all` in the main project directory to prepare the images. It will rebuild anything where the source code changed since last time.

## Build contract-examples

Contract examples exist in another repo.  Clone https://github.com/CasperLabs/contract-examples and follow the root [README.md](https://github.com/CasperLabs/contract-examples/blob/master/README.md) for build instructions.  You will need to have performed the Rust developer environment setup section in [DEVELOPER.md](https://github.com/CasperLabs/CasperLabs/blob/dev/DEVELOPER.md) in the root of this repo.

## Required: docker-compose

`docker-compose` is used to bring up the nodes on the network. Please verify that [docker-compose is installed](https://docs.docker.com/compose/install/) prior to continuing.

## Required: OpenSSL 1.1

`openssl` is used to generate keys and certificates. Please verify that [the latest OpenSSL 1.1 version is installed](https://github.com/openssl/openssl). You can also [follow these steps](https://github.com/CasperLabs/CasperLabs/blob/dev/VALIDATOR.md#setting-up-keys)

## Required: SHA3SUM

`keccak-256sum` is used to generate node TLS certificate. Please verify that [sha3sum is installed](https://github.com/maandree/sha3sum).


## Set up a network

We will create multiple nodes in docker with names such as `node-0`, `node-1` etc. Each will have a corresponding container running the Execution Engine.

The setup process will establish validator keys in `.casperlabs/node-*` and bonds in `.casperlabs/genesis` by executing [docker-gen-keys.sh](/hack/key-management/docker-gen-keys.sh). By default 3 nodes' keys are created but you can generate more by setting the `CL_CASPER_NUM_VALIDATORS` variable.

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

## Deploy some WASM code

Assuming that you cloned and compiled the [contract-examples](https://github.com/CasperLabs/contract-examples) you can deploy them by running the following:

```console
ACCOUNT_ID="$(cat .casperlabs/genesis/system-account/account-id-hex)"
./client.sh node-0 deploy $PWD/../../../contract-examples/hello-name/define/target/wasm32-unknown-unknown/release\
     --from "$ACCOUNT_ID" \
     --gas-price 1 \
     --session /data/helloname.wasm \
     --payment /data/helloname.wasm \
     --nonce 1
```

As you may notice we make use of the `system-account` for deploys signing. This is temporal until we the add ability to create new custom accounts.

After a successful deploy, you should see the following response:

```
Success!
```

At the moment you have to trigger block proposal by invoking the following command:

```console
./client.sh node-0 propose
```

After a successful deploy, the response will contain the block ID:

```
Response: Success! Block f876efed8d... created and added.
```

If you check the log output, each node should get the block and provide some feedback about the execution as well.

### Signing Deploys

To sign deploy you'll need to [generate and ed25519 keypair](/VALIDATOR.md#setting-up-keys) and save them into `docker/keys`. The `client.sh` script will automatically mount this as a volume and you can pass them as CLI arguments, for example:

```console
ACCOUNT_ID="$(cat .casperlabs/genesis/system-account/account-id-hex)"
mkdir keys
cp .casperlabs/genesis/system-account/account-private.pem keys
cp .casperlabs/genesis/system-account/account-public.pem keys
./client.sh node-0 deploy $PWD/../../../contract-examples/hello-name/define/target/wasm32-unknown-unknown/release\
     --gas-price 1 \
     --from "$ACCOUNT_ID" \
     --session /data/helloname.wasm \
     --payment /data/helloname.wasm \
     --nonce 1 \
     --public-key /keys/account-0/account-public.pem \
     --private-key /keys/account-0/account-private.pem
```

As you may notice we make use of the `system-account` for deploys signing. This is temporal until we the add ability to create new custom accounts.

## Monitoring

### Prometheus

Running `make up` will install some common containers in the network, for example a [Prometheus](https://prometheus.io) server which will be available at http://localhost:9090. The list of [targets](http://localhost:9090/targets) will be updated every time we create or destroy nodes.

### Grafana

To see some of the metrics in [Grafana](https://grafana.com/) go to http://localhost:3000 and log in with the credentials "admin/admin" and skip prompt to change password.  This is just a local instance of Grafana, so only accessible from localhost.

The Block Gossiping dashboard will display charts that show communication overhead.  Click on the dashboards (2x2 blocks) icon on the left if you don't see the Block Gossiping dashboard link.

Note that you'll need to run `docker login` with your DockerHub username and password to be able to pull 3rd party images.

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
