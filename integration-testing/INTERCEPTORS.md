# Interceptors 

## Overview

In order to test CasperLabs network's response to nodes exhibiting malicious or erroneous behaviour,
the integration test framework has been extended 
with capability to intercept communication 
between nodes and modify, censor or add additional messages. 

This allows to test certain scenarios
that cannot be simulated
with a vanilla CasperLabs node,
which is meant to always behave correctly.

To achieve this a generic gRPC proxy has been developed in Python.
A proxy is set up to receive all incoming traffic
to a node in the test network.
It is then normally passing the messages (for example: blocks) to the node,
but before doing so it can modify the message or decide not to pass it to the node at all. 
Capability to send extra messages from the proxy to the node
is planned to be added in the next stage.

Responses from the node can also be modified or censored by the proxy.

## Implementation

### gRPC Proxy

The generic gRPC Python proxy `grpc_proxy.py` is a submodule of `casperlabs_local_net`.

Instantiating a proxy can be done by calling one of the following functions in `grpc_proxy.py`:
- `proxy_server`, for the Gossip (a.k.a. "protocol") service proxy,
- `proxy_kademlia`, for Kademlia (a.k.a. "discovery"),
- `proxy_client`, for the client's gRPC endpoint (a.k.a. "casper").

All of those functions accept following parameters:
- `node_host`, IP or hostname of the node behind the proxy,
- `node_port`, port that the node serves on,
- `proxy_port`, port that the proxy serves on.
- `interceptor`, an instance of the `Interceptor` class, described below.

Additionally, `proxy_client` and `proxy_server` accept parameters
needed for connection encryption (SSL).
The Kademlia service is currently not encrypted.

All the three functions spawn a server thread that serves requests on a given `proxy_port`,
redirecting the requests to node on `node_host` and `node_port`.
Return value of each of the functions is an instance of `ProxyThread` with a `stop` method.

### Interceptor

`Interceptor` is a class in `grpc_proxy.py` which has following methods:
- `pre_request`, which a proxy calls when it receives a new request.
This method accepts a gRPC method name and a request object.
It is supposed to return a request
that the proxy will pass to the node.
The default implementation returns unchanged request object that it received as input;
before doing it it logs its parameters.
- `post_request` and `post_request_stream`,
called by proxy after receiving response from node.
The methods are supposed to return a response
that will be passed by the proxy to the proxy's client (node that issued the request).
`post_request` will be called for `unary_unary` gRPC methods,
`post_request_stream` for `unary_stream` gRPC methods.
Default implementations of these methods return unchanged response received from the node behind the proxy
and, earlier, do some logging.

### Setup of test networks

There is currently one test network in the integration tests framework,
`InterceptedTwoNodeNetwork`
(available in tests as `pytest` fixture `intercepted_two_node_network`),
that creates a network with nodes set up to be hidden behind proxies
(one for gossip and one for the Kademlia service).

Creation of the proxies as attributes of a `DockerNode` instance
is triggered by a flag `behind_proxy` in `DockerConfig`.

The bootstrap node normally runs the Gossip service on port 40400.
In the setup with proxies it will run the service on port 50400,
and run the proxy on port 40400.
Simlarly, the Kademlia (discovery) service will run on 50404 instead of 40404,
and a Kademlia proxy will serve on the ususal 40404.

The nodes that boostrap from the bootstrap node will be given a "fake" address of the node,
with hostname of the Docker container where the test framework runs (when run in CI)
and `protocol` and `discovery` pointing to ports of the respective proxies,
for example: 

`casperlabs://4d802045c3e4d2e031f25878517bc8e2c9710ee7@test-test-DRONE-1?protocol=40400&discovery=40404`

where the real address of the node behind the proxy is:

`casperlabs://4d802045c3e4d2e031f25878517bc8e2c9710ee7@node-0-ftvoq-test-DRONE-1?protocol=50400&discovery=50404`

#### Running locally vs running in CI

The setup of the integration test framework is slightly different
when it is run by Drone as part of CI
comparing to when it's run locally on the developer's machine with the `run_tests.sh` script.

In CI the Python tests run in a Docker container.
The tests address nodes running in their containers
using hostnames assigned to them in the Docker network by the Docker.
Each node uses the same port numbers,
40400 for the Gossip server, 
40404 for Kademlia.

In contrast, the test framework run locally with `run_tests.sh`
runs as a regular Python process on the host machine that communicates with nodes
via ports exposed by their docker containers on the host machine.
In other words, 
in this setup,
from the point of view of the test framework,
all nodes run on the `localhost`,
thus they must expose their ports mapping them to different ports on the host machine.
