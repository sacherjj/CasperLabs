import threading

from . import conftest
from test.cl_node.docker_node import DockerNode
from .cl_node.common import random_string
from .cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from .cl_node.wait import (
    wait_for_blocks_count_at_least,
)

from .cl_node.casperlabsnode import ( extract_block_hash_from_propose_output, )

import pytest
from typing import List

from .cl_node.casperlabs_network import ThreeNodeNetwork

BOOTSTRAP_NODE_KEYS = PREGENERATED_KEYPAIRS[0]


def create_volume(docker_client) -> str:
    volume_name = "casperlabs{}".format(random_string(5).lower())
    docker_client.volumes.create(name=volume_name, driver="local")
    return volume_name


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
    def __init__(self, name: str, node: DockerNode, batches_of_contracts: List[List[str]]) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.batches_of_contracts = batches_of_contracts 
        self.deployed_blocks_hashes = set()

    def propose(self):
        propose_output = self.node.client.propose()
        return extract_block_hash_from_propose_output(propose_output)

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.client.deploy(session_contract = contract, payment_contract = contract)
            block_hash = self.propose()
            self.deployed_blocks_hashes.add(block_hash)


@pytest.fixture(scope='function')
def n(request):
    return request.param

# This fixture will be parametrized so it runs on node set up to use gossiping as well as the old method.
@pytest.fixture()
def three_nodes(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture, extra_docker_params={'use_new_gossiping': True}) as network:
        network.create_cl_network()
        yield [node.node for node in network.cl_nodes]


@pytest.mark.parametrize("contract_paths, expected_number_of_blocks", [

                         ([['test_helloname.wasm'],['test_helloworld.wasm']], 7),

                         ])
# Curently nodes is a network of three bootstrap connected nodes.
def test_block_propagation(three_nodes,
                           contract_paths: List[List[str]], expected_number_of_blocks):
    """
    Feature file: consensus.feature
    Scenario: test_helloworld.wasm deploy and propose by all nodes and stored in all nodes blockstores
    """

    deploy_threads = [DeployThread("node" + str(i+1), node, contract_paths)
                      for i, node in enumerate(three_nodes)]

    for t in deploy_threads:
        t.start()

    for t in deploy_threads:
        t.join()

    for node in three_nodes:
        wait_for_blocks_count_at_least(node, expected_number_of_blocks, expected_number_of_blocks * 2, node.timeout)

    for node in three_nodes:
        blocks = parse_show_blocks(node.client.show_blocks(expected_number_of_blocks * 100))
        # What propose returns is first 10 characters of block hash, so we can compare only first 10 charcters.
        blocks_hashes = set([b.blockHash[:10] for b in blocks])
        for t in deploy_threads:
            assert t.deployed_blocks_hashes.issubset(blocks_hashes), \
                   f"Not all blocks deployed and proposed on {t.name} were propagated to {node.name}"
        

