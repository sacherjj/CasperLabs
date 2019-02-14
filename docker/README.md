# Node network simulation

The idea is to create many nodes with commands like `make node-0/up`, `make node-1/down` etc. Each will have the same configuration as `template/Dockerfile` by having a symlink. The containers would all be on the same network.

Then we can slow down the network between `node-*` containers with https://alexei-led.github.io/post/pumba_docker_netem/ and see what happens.

To deploy we'll have to `docker run --network casperlabs io.casperlabs/client` and pass it the WASM files. `client.sh` provides is a convenience wrapper for interacting with the network; run `./client.sh node-0 --help` to see what it can do.


## Build docker images

Run `make docker-build-all` in the main project directory to prepare the images. It will rebuild anything where the source code changed since last time.


## Set up a network

We'll set up a bunch of nodes in docker with names such as `node-0`, `node-1` etc. Each will have a corresponding container running the Execution Engine.

The setup process will establish validator keys and bonds in `.casperlabs/genesis` by starting a node instance once up front. By default 10 files are created but you can generate more by setting the `CL_CASPER_NUM_VALIDATORS` variable.

`node-0` will be the bootstrap node that all subsequent nodes connect to, so create that first. You can run `make node-0` to establish its directory and see the values `docker-compose` will use, or just run `make node-0/up` to bring up the node in docker straight away. Check that everything is fine with `docker logs -f node-0`. When it's running, bring up more nodes.


## Deploy some WASM code

Assuming that you compiled the example WASM contracts as described [here](https://slack-files.com/TDVFB45LG-FFBGDQSBW-bad20239ec) you can deploy them by running the following:

```sh
$ ./client.sh node-0 deploy $PWD/../contract-examples/store-hello-name/target/wasm32-unknown-unknown/release\
>     --from 00000000000000000000 \
>     --gas-limit 100000000 --gas-price 1 \
>     --session /data/helloname.wasm \
>     --payment /data/helloname.wasm

Success!
$ ./client.sh node-0 propose
Response: Success! Block f876efed8d... created and added.
$
```

## Monitoring

Running `make up` will install some common containers in the network, for example a [Prometheus](https://prometheus.io) server which will be available at http://localhost:9090. The list of [targets](http://localhost:9090/targets) will be updated every time we create or destroy nodes.

To see some of the metrics in [Grafana](https://grafana.com/) go to http://localhost:3000 and log in with the credentials "admin/admin". The Block Gossiping dashboard displays charts that show how much overhead the communication has.

You can slow the network down a bit by running `make delay` in one terminal while issuing deploys in another one. You should see that now it takes longer for nodes to catch up after a block is created; even just sending the deploy is going to take a bit more time.

Note that you'll need to run `docker login` with your DockerHub username and password to be able to pull 3rd party images.


## Visualizing the DAG

You'll need to `sudo apt-get install graphviz` to run the following code.

```sh
./client.sh node-0 vdag --showJustificationlines --depth 25 \
    | dot -Tpng -o /tmp/cl-dag.png \
    && xdg-open /tmp/cl-dag.png
```


## Shut down the network

You can tear everything down by running `make clean`, or destroy individual nodes with `make node-1/down` for example.