Node network simulation
-----------------------

The idea is to create many nodes with commands like `make up/node-0`, `make up/node-1` etc. Each will have the same configuration as `template/Dockerfile` by having a symlink. The containers would all be on the same network.

Then we can slow down the network between `nodes` with https://alexei-led.github.io/post/pumba_docker_netem/ and see what happens.

To deploy we'll have to `docker run io.casperlabs/client` and pass it the WASM files. Will need a shell script wrapper.