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
from typing import List


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


@pytest.fixture
def nodes(command_line_options_fixture, docker_client_fixture, timeout, bootstrap_contract_path = 'helloname.wasm'):
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
    

class DeployThread(threading.Thread):
    def __init__(self, name: str, node: Node, batches_of_contracts: List[List[str]]) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.batches_of_contracts = batches_of_contracts 

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.deploy(session = contract, payment = contract)
            self.node.propose()


@pytest.mark.parametrize("contract_paths,expected_deploy_counts_in_blocks", [

                         # Nodes deploy one or more contracts followed by propose.

                         # Only first helloname.wasm will not fail to be deployed and proposed. 
                         # Propose only picks out one of the deploys because
                         # it is not allowed to pick deploys that conflict (write the same key) any more.
                         # helloworld.wasm fails because helloname.wasm was not deployed yet.
                         ([['helloname.wasm'],['helloworld.wasm']], [1, 1, 1, 1, 1, 1, 0]),
])
# Curently nodes is a network of three bootstrap connected nodes.
def test_multiple_deploys_at_once(nodes, timeout,
                                  contract_paths: List[List[str]], expected_deploy_counts_in_blocks):
    deploy_threads = [DeployThread("node" + str(i+1), node, contract_paths)
                      for i, node in enumerate(nodes)]

    for t in deploy_threads:
        t.start()

    for i in deploy_threads:
        t.join()

    # See COMMENT_EXPECTED_BLOCKS 
    for node in nodes:
        wait_for_blocks_count_at_least(node, len(expected_deploy_counts_in_blocks), len(expected_deploy_counts_in_blocks) * 2, timeout)

    for node in nodes:
        blocks = parse_show_blocks(node.show_blocks_with_depth(len(expected_deploy_counts_in_blocks) * 100))
        assert [b.deployCount for b in blocks] == expected_deploy_counts_in_blocks, 'Unexpected deploy counts in blocks'

