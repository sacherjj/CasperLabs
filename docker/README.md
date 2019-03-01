# Node network simulation

The idea is to create many nodes with commands like `make node-0/up`, `make node-1/down` etc. Each will have the same configuration as `template/Dockerfile` by having a symlink. The containers would all be on the same network.

Then we can slow down the network between `node-*` containers with https://alexei-led.github.io/post/pumba_docker_netem/ and see what happens.

To deploy we'll have to `docker run --network casperlabs casperlabs/client` and pass it the WASM files. `client.sh` provides is a convenience wrapper for interacting with the network; run `./client.sh node-0 --help` to see what it can do.

## Build docker images

Run `make docker-build-all` in the main project directory to prepare the images. It will rebuild anything where the source code changed since last time.

## Build contract-examples

Contract examples exist in another repo.  Clone https://github.com/CasperLabs/contract-examples and follow the root [README.md](https://github.com/CasperLabs/contract-examples/blob/master/README.md) for build instructions.  You will need to have performed the Rust developer environment setup section in [DEVELOPER.md](https://github.com/CasperLabs/CasperLabs/blob/dev/DEVELOPER.md) in the root of this repo.

## Set up a network

We'll set up a bunch of nodes in docker with names such as `node-0`, `node-1` etc. Each will have a corresponding container running the Execution Engine.

The setup process will establish validator keys and bonds in `.casperlabs/genesis` by starting a node instance once up front. By default 10 files are created but you can generate more by setting the `CL_CASPER_NUM_VALIDATORS` variable.

`node-0` will be the bootstrap nodemake node-0/upthat all subsequent nodes connect to, so create that first. You can run `make node-0` to establish its directory and see the values `docker-compose` will use, or just run `make node-0/up` to bring up the node in docker straight away.

```console
$ make node-0/up
...
Creating execution-engine-0 ... done
Creating node-0 ... done
```

We can check that everything is fine with:

```bash
$ docker logs node-0
```

To follow the log in a terminal and watch messages while you bring up other nodes in a new terminal, use the `-f | --follow` flag as in `docker logs -f node-0`.

Now we will bring up a second node:

```bash
$ make node-1/up
...
Creating execution-engine-1 ... done
Creating node-1 ... done
```

You will be able to see that in both node logs, we have `Peers: 1`, showing they have connected.

New we bring up a third node:

```bash
$ make node-2/up
...
Creating execution-engine-2 ... done
Creating node-2 ... done
```

After connection is complete, all node logs will show `Peers: 2`.

## Deploy some WASM code

Assuming that you cloned and compiled the [contract-examples](https://github.com/CasperLabs/contract-examples) you can deploy them by running the following:

```sh
$ ./client.sh node-0 deploy $PWD/../../contract-examples/hello-name/define/target/wasm32-unknown-unknown/release\
     --from 00000000000000000000 \
     --gas-limit 100000000 --gas-price 1 \
     --session /data/helloname.wasm \
     --payment /data/helloname.wasm

Success!
$ ./client.sh node-0 propose
Response: Success! Block f876efed8d... created and added.
$
```

## Monitoring

### Prometheus

Running `make up` will install some common containers in the network, for example a [Prometheus](https://prometheus.io) server which will be available at http://localhost:9090. The list of [targets](http://localhost:9090/targets) will be updated every time we create or destroy nodes.

### Grafana

To see some of the metrics in [Grafana](https://grafana.com/) go to http://localhost:3000 and log in with the credentials "admin/admin" and skip prompt to change password.  This is just a local instance of Grafana, so only accessible from localhost.  

The Block Gossiping dashboard will display charts that show communication overhead.  Click on the dashboards (2x2 blocks) icon on the left if you don't see the Block Gossiping dashboard link.  

Note that you'll need to run `docker login` with your DockerHub username and password to be able to pull 3rd party images.

## Network Effects

You can slow the network down a bit by running `make delay` or `make slow` in one terminal while issuing deploys in another one. You should see that now it takes longer for nodes to catch up after a block is created; even just sending the deploy is going to take a bit more time.

Another way to introduce a network partition is to for example create two networks, put the bootstrap node in both of them, but the rest of the nodes in just one, half here, half there. Then either slow down just the bootstrap node by introducing packet loss using the [netem](https://alexei-led.github.io/post/pumba_docker_netem/) tool, or by running `docker exec -it --privileged node-0 sh` and using `iptables`:

```sh
# Drop traffic going to the intra-node gRPC port:
iptables --append INPUT --protocol tcp --destination-port 40400 --jump DROP
# Later delete the rule to restore traffic:
iptables --delete INPUT --protocol tcp --destination-port 40400 --jump DROP
```

The effect should be that because the bootstrap node never sees any gossiping the two halves of the network can build the chain independently for a while. When the bootstrap node is restored and sees a new block, it will try to catch up with the missing parts of the DAG and forward it to its peers, reconnecting the two halves.

You can also have a look at [using tc to slow down a specific port](https://stackoverflow.com/questions/10694730/in-linux-simulate-slow-traffic-incoming-traffic-to-port-e-g-54000). The benefit of using `iptables` and `tc` on individual port rather than the node level like `make delay` is that the deployment and metrics endpoints can stay unaffected.

_NOTE_: For the techniques that use `iptables` and `tc` to work you'll need to run the `test` version of the node image which you should have if you built the images yourself with `make docker-build-all` in the top directory, and subsequently set `export CL_VERSION=test` before creating the containers. You can set the the environment variable later as well, then run run `make node-0/up` again and see `docker-compose` recreating the containers with the new version.

## Visualizing the DAG

You'll need to `sudo apt-get install graphviz` to run the following code:

```sh
$ ./client.sh node-0 vdag --show-justification-lines --depth 25 \
    | dot -Tpng -o /tmp/cl-dag.png \
    && xdg-open /tmp/cl-dag.png
```

Alternatively if you don't wish to install `graphviz` on your machine you can use just the browser:

```sh
$ google-chrome --new-window \
    $(python -c "import urllib; print 'https://dreampuf.github.io/GraphvizOnline/#' + urllib.quote('''$(./client.sh node-0 vdag --show-justification-lines --depth 25)''')")
```

## Shut down the network

You can tear everything down by running `make clean`, or destroy individual nodes with `make node-1/down` for example.

```basic
$ make node-1/down
...
Stopping node-1             ... done
Stopping execution-engine-1 ... done
Removing node-1             ... done
Removing execution-engine-1 ... done
```

```bash
$ make clean
docker-compose -p casperlabs down
Stopping prometheus ... done
Stopping grafana    ... done
Removing prometheus ... done
Removing grafana    ... done
...
Stopping node-0             ... done
Stopping execution-engine-0 ... done
Removing node-0             ... done
Removing execution-engine-0 ... done
Network casperlabs is external, skipping
...
Stopping node-2             ... done
Stopping execution-engine-2 ... done
Removing node-2             ... done
Removing execution-engine-2 ... done
...
docker network rm casperlabs || exit 0
casperlabs
rm -rf .casperlabs
rm -rf .make
```
