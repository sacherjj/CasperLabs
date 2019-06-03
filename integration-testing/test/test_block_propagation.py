import threading

from . import conftest
from test.cl_node.docker_node import DockerNode
from test.cl_node.client_parser import parse_show_blocks
from .cl_node.common import random_string
from .cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from .cl_node.wait import (
    wait_for_blocks_count_at_least,
)

from .cl_node.casperlabsnode import ( extract_block_hash_from_propose_output, )

import pytest
from typing import List
import ed25519

from .cl_node.casperlabs_network import ThreeNodeNetwork

BOOTSTRAP_NODE_KEYS = PREGENERATED_KEYPAIRS[0]


def generate_keys(prefix):
    signing_key_file, verifying_key_file = f'{prefix}_signing_key', f'{prefix}_verifying_key'

    signing_key, verifying_key = ed25519.create_keypair()

    def write_key(key_file, key):
        with open(key_file, 'wb') as f:
            f.write(key.to_bytes())

    write_key(signing_key_file, signing_key) 
    write_key(verifying_key_file, verifying_key) 
        
    return signing_key_file, verifying_key_file


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
                assert 'Success' in self.node.client.deploy(session_contract = contract,
                                                            payment_contract = contract)
            block_hash = self.propose()
            self.deployed_blocks_hashes.add(block_hash)


@pytest.fixture(scope='function')
def n(request):
    return request.param

@pytest.fixture()
def three_nodes(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture, extra_docker_params={'use_new_gossiping': True}) as network:
        network.create_cl_network()
        yield network.docker_nodes


@pytest.mark.parametrize("contract_paths, expected_number_of_blocks", [

                         ([['test_helloname.wasm'],['test_helloworld.wasm']], 5),

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
        blocks_hashes = set([b.block_hash[:10] for b in blocks])
        for t in deploy_threads:
            assert t.deployed_blocks_hashes.issubset(blocks_hashes), \
                   f"Not all blocks deployed and proposed on {t.name} were propagated to {node.name}"


