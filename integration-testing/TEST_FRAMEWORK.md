## Test Framework

The purpose of this document is to give an over view of the new test framework and how to add tests with it.

## Core Objects

There is confusion around the term "Node" when talking about CasperLabs objects.  The test framework has broken the
idea of `Node` into two types.  `DockerNode` and `CasperLabsNode`.  `DockerNode` is a helper object for handling a
`casperlabs/node` docker containers.  `CasperLabsNode` is a combination of `DockerNode`, `DockerExecutionEngine` and 
a client for interacting with the `DockerNode`.  

#### DockerConfig (docker_base.py)

`DockerConfig` object is a dataclass that should hold all unique information for starting up a `CasperLabsNode`.

#### DockerBase (docker_base.py)

`DockerBase` handles management of a docker container.  This includes many properties that are generated based on
values given by subclasses.  `_get_container` method is required by a subclass to stand up the docker container for
that class.  `cleanup` method requires call during teardown.

#### LoggingDockerBase (docker_base.py) from DockerBase

From `DockerBase` this adds a background logging thread for a docker container.    

#### DockerExecutionEngine (docker_execution_engine.py) from LoggingDockerBase

Manages a `casperlabs/execution-engine` docker container.  Shares a docker volume with DockerNode container for 
communication.

#### DockerNode (docker_node.py) from LoggingDockerBase

 Manages a `casperlabs/node` docker container and client to communicate with this node.  Current implementation is 
 using a `casperlabs/client` docker container for this and we will integrate the new Python client.
 
 This object creates a resources directory in `/tmp` for each `DockerNode` instance.  This holds all files used by
 the `casperlabs/node`.
 
 This object holds most methods used during interaction with the client, such as `deploy`, `propose`, `show_blocks`, etc.
 
 #### CasperLabsNode (casperlabs_node.py)
 
 `CasperLabsNode` holds a `DockerExecutionEngine` and `DockerNode` and creates a volume in docker for the socket 
 communication between the two.  One `DockerConfig` is passed to both objects and must contain all data needed to 
 stand up both `Docker*` objects.
 
 #### CasperLabsNetwork (casperlabs_network.py)
 
 `CasperLabsNetwork` is the one object that is needed to be stood up for a complete functioning network of nodes.
 
 `CasperLabsNodes` are created and numbered from 0.  The bootstrap node is always 0.
 
 `add_bootstrap` is called first for the bootstrap node-0.
 
 `add_cl_node` is called for each node after.  By default, these nodes will join with the network created with the
 bootstrap.  This may not be desired as we make more complex networks.  
 
 `CasperLabsNetwork` is a contextmanager and handles clean teardown of all objects in the network.
 
 Any subclass must implement `create_cl_network` to stand up the network.  Arguments can be added to control functionality.
 
 Test fixtures to stand up `CasperLabsNetwork`s are created in `conftest.py`.  Here is an example of the simplest:
 
    @pytest.fixture()
    def one_node_network(docker_client_fixture):
        with OneNodeNetwork(docker_client_fixture) as onn:
            onn.create_cl_network()
            yield onn
 
 ### Adding tests
 
 The framework uses `pytest`.  Any files with a prefix of `test_` in the `test` directory will be picked up for tests.
 
 Methods in these files with `test_` prefix will be executed as tests.
 
 If a `CasperLabsNetwork` class and fixture exist for the test, then you only need to call it for your test.
 
     def test_my_new_test(one_node_network):
         node = one_node_network.docker_nodes[0]
         # At this point, network is completely stood up and node is available for methods.
         
     def test_my_other_new_test(three_node_network):
         node0, node1, node2 = three_node network.docker_nodes    
         # Three CasperLabsNodes ready for operation.