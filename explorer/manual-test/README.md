# Manual Testing

This directory holds tests of the client into the network
with `CasperLabs/hack/docker`.

 - Run `./build_casperlabs.sh` to have any wasm used built and build docker iamges.
 - Run `./standup.sh` to bring up 3 node network.
 - Run python tests.
 - Run `./teardown.sh` to bring down network when finished.

This assumes that `client-py` repo is at the same directory level as the 
`CasperLabs` repo.  