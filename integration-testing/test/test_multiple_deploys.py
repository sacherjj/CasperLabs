import threading

from . import conftest
from .cl_node.casperlabsnode import (
    Node,
    bootstrap_connected_peer,
    docker_network_with_started_bootstrap,
)
from .cl_node.common import random_string
from .cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from .cl_node.wait import (
    wait_for_blocks_count_at_least,
    wait_for_peers_count_at_least,
)

import pytest


BOOTSTRAP_NODE_KEYS = PREGENERATED_KEYPAIRS[0]

def create_volume(docker_client) -> str:
    volume_name = "casperlabs{}".format(random_string(5).lower())
    docker_client.volumes.create(name=volume_name, driver="local")
    return volume_name


# An explanation given by @Akosh about number of expected blocks.
# This is why the expected blocks are 4.
COMMENT_EXPECTED_BLOCKS = """
I think there is some randomness to it. --depth gives you the last
layers in the topological sorting of the DAG, which I believe is
stored by rank.
I'm not sure about the details, for example if we have
`G<-B1, G<-B2, B1<-B3` then G is rank 0, B1 and B2 are rank 1 and
B3 is rank 2. But we could also argue that a depth of 1 should
return B3 and B2.

Whatever --depth does, the layout of the DAG depends on how the
gossiping went when you did you proposals. If you did it real slow,
one by one, then you might have a chain of 8 blocks
(for example `G<-A1<-B1<-B2<-C1<-B3<-C2<-C3`), all linear;
but if you did it in perfect parallelism you could have all of them
branch out and be only 4 levels deep
(`G<-A1, G<-B1, G<-C1, [A1,B1,C1]<-C2`, etc).
"""


@pytest.fixture
def nodes(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(command_line_options_fixture, docker_client_fixture,
                                  bootstrap_keypair=BOOTSTRAP_NODE_KEYS, peers_keypairs=PREGENERATED_KEYPAIRS[1:]) as context:
        with docker_network_with_started_bootstrap(context=context) as bootstrap_node:
            volume_names = [create_volume(docker_client_fixture) for _ in range(3)]
            peers = [bootstrap_connected_peer(name = 'bonded-validator-'+str(i+1),
                                              keypair = PREGENERATED_KEYPAIRS[i+1],
                                              socket_volume = volume_name,
                                              context = context,
                                              bootstrap = bootstrap_node)
                     for i, volume_name in enumerate(volume_names)]
            # TODO: change bootstrap_connected_peer so the lines below are not needed
            with peers[0] as n0, peers[1] as n1, peers[2] as n2:
                wait_for_peers_count_at_least(bootstrap_node, 3, context.node_startup_timeout)
                yield [n0, n1, n2]

            # Tear down.
            for v in volume_names:
                docker_client_fixture.volumes.get(v).remove(force=True)
    

@pytest.fixture
def timeout(command_line_options_fixture):
    return command_line_options_fixture.node_startup_timeout


def parse_value(s):
    try:
        return int(s)
    except ValueError:
        return s[1:-1] # unquote string

        
def parse_line(line):
    k, v = line.split(': ')
    return k, parse_value(v)


def parse_block(s):

    class Block:
        def __init__(self, d):
            self.d = d

        def __getattr__(self, name):
            return self.d[name]

    return Block(dict(parse_line(line) for line in filter(lambda line: ':' in line, s.splitlines())))


def parse_show_blocks(s):
    """
    TODO: remove this and related functions once Python client is integrated into test framework.
    """
    blocks = s.split('-----------------------------------------------------')[:-1]
    return [parse_block(b) for b in blocks]


class DeployThread(threading.Thread):
    def __init__(self, name: str, node: Node, contracts: str) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.contracts = contracts

    def run(self) -> None:
        for contract in self.contracts:
            self.node.deploy(session = contract, payment = contract)
        self.node.propose()


@pytest.mark.parametrize("contract_path,expected_blocks_count", [
                          # Bootstrap node will only deploy the first contract.
                          # Rest of the nodes will deploy all contracts from the list.
                          # Deploying helloname.wasm second time should be idempotent, according to Michael.
                        (['helloname.wasm','helloname.wasm','helloworld.wasm',], 4)
])
# Curently nodes is a network of three nodes, the first one is the bootstrap node.
def test_multiple_deploys_at_once(nodes, timeout, contract_path, expected_blocks_count):
    bootstrap_node = nodes[0]

    bootstrap_deploy_thread = DeployThread("node0", bootstrap_node, [contract_path[0]])
    rest_of_deploy_threads = [DeployThread("node" + str(i+1), node, contract_path)
                              for i, node in enumerate(nodes[1:])]

    bootstrap_deploy_thread.start()
    wait_for_blocks_count_at_least(bootstrap_node, 1, 1, timeout)

    for t in rest_of_deploy_threads:
        t.start()

    for i in [bootstrap_deploy_thread] + rest_of_deploy_threads:
        t.join()

    # See COMMENT_EXPECTED_BLOCKS 
    for node in nodes:
        wait_for_blocks_count_at_least(node, expected_blocks_count, expected_blocks_count * 2, timeout)

    for node in nodes:
        # Check expected deploy numbers in blocks
        assert [b.deployCount for b in parse_show_blocks(node.show_blocks_with_depth(10))] == [3, 3, 1, 0]  # That's what we see for 3 nodes.

