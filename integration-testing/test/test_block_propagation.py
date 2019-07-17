import threading
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.docker_node import DockerNode
from test.cl_node.errors import NonZeroExitCodeError
from typing import List
import pytest
import logging

from . import conftest
from .cl_node.casperlabs_network import ThreeNodeNetwork, \
    CustomConnectionNetwork
from .cl_node.casperlabsnode import extract_block_hash_from_propose_output
from .cl_node.common import random_string
from .cl_node.wait import (wait_for_blocks_count_at_least,
                           wait_for_block_hash_propagated_to_all_nodes,
                           wait_for_block_hashes_propagated_to_all_nodes,
                           wait_for_peers_count_at_least, wait_for_peers_count_exactly)


class DeployThread(threading.Thread):
    def __init__(self,
                 node: DockerNode,
                 batches_of_contracts: List[List[str]],
                 max_attempts: int,
                 retry_seconds: int) -> None:
        threading.Thread.__init__(self)
        self.node = node
        self.batches_of_contracts = batches_of_contracts
        self.deployed_block_hashes = set()
        self.max_attempts = max_attempts
        self.retry_seconds = retry_seconds

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.client.deploy(
                    session_contract=contract,
                    payment_contract=contract,
                    private_key="validator-0-private.pem",
                    public_key="validator-0-public.pem")

            block_hash = self.node.client.propose_with_retry(self.max_attempts, self.retry_seconds)
            self.deployed_block_hashes.add(block_hash)


@pytest.fixture()
def nodes(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as network:
        network.create_cl_network()
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        yield network.docker_nodes


@pytest.mark.parametrize("contract_paths, expected_number_of_blocks", [
    ([['test_helloname.wasm'], ['test_helloworld.wasm']], 7),
])
def test_block_propagation(nodes,
                           contract_paths: List[List[str]],
                           expected_number_of_blocks):
    """
    Feature file: consensus.feature
    Scenario: test_helloworld.wasm deploy and propose by all nodes and stored in all nodes blockstores
    """

    deploy_threads = [DeployThread(node, contract_paths, max_attempts=5, retry_seconds=3) for node in nodes]

    for t in deploy_threads:
        t.start()

    deployed_block_hashes = set()
    for t in deploy_threads:
        t.join()
        deployed_block_hashes.update(t.deployed_block_hashes)

    wait_for_block_hashes_propagated_to_all_nodes(nodes, deployed_block_hashes)


def deploy_and_propose(node, contract, nonce=None):
    assert 'Success' in node.client.deploy(session_contract=contract,
                                           payment_contract=contract,
                                           private_key="validator-0-private.pem",
                                           public_key="validator-0-public.pem",
                                           nonce=nonce)
    propose_output = node.client.propose()
    return extract_block_hash_from_propose_output(propose_output)


@pytest.fixture()
def not_all_connected_directly_nodes(docker_client_fixture):
    """
    node0 -- node1 -- node2
    """
    with CustomConnectionNetwork(docker_client_fixture) as network:
        # All nodes need to be connected to bootstrap in order to download the genesis block.
        network.create_cl_network(3, [(0, 1), (1, 2), (0, 2)])
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        # All nodes have the genesis block now, so we can disconnect one from the bootstrap.
        network.disconnect((0, 2))
        yield network.docker_nodes


def test_blocks_infect_network(not_all_connected_directly_nodes):
    """
    Feature file: block_gossiping.feature
    Scenario: Blocks 'infect' the network and nodes 'closest' to the propose see the blocks first.
    """
    first, last = not_all_connected_directly_nodes[0], \
                  not_all_connected_directly_nodes[-1]

    block_hash = deploy_and_propose(first, 'test_helloname.wasm')
    wait_for_block_hash_propagated_to_all_nodes([last], block_hash)


@pytest.fixture()
def four_nodes_network(docker_client_fixture):
    with CustomConnectionNetwork(docker_client_fixture) as network:
        # Initially all nodes are connected to each other
        network.create_cl_network(4,
                                  [(i, j) for i in range(4) for j in range(4) if
                                   i != j and i < j])

        # Wait till all nodes have the genesis block.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)

        yield network


C = ["test_helloname.wasm", "test_mailinglistdefine.wasm",
     "test_helloworld.wasm"]


def test_network_partition_and_rejoin(four_nodes_network):
    """
    Feature file: block_gossiping.feature
    Scenario: Network partition occurs and rejoin occurs
    """
    # Partition the network so node0 connected to node1 and node2 connected to node3 only.
    connections_between_partitions = [(i, j) for i in (0, 1) for j in (2, 3)]
    logging.info("PARTITIONS: {}".format(connections_between_partitions))

    logging.info("DISCONNECT PARTITIONS")
    for connection in connections_between_partitions:
        logging.info("DISCONNECTING PARTITION: {}".format(connection))
        four_nodes_network.disconnect(connection)

    nodes = four_nodes_network.docker_nodes
    n = len(nodes)
    partitions = nodes[:int(n / 2)], nodes[int(n / 2):]
    logging.info("PARTITIONS: {}".format(partitions))

    # Node updates its list of alive peers in background with a certain period
    # So we need to wait here for nodes to re-connect partitioned peers
    for partition in partitions:
        for node in partition:
            wait_for_peers_count_exactly(node, len(partition) - 1, 60)

    # Propose separately in each partition. They should not see each others' blocks,
    # so everyone has the genesis plus the 1 block proposed in its partition.
    # Using the same nonce in both partitions because otherwise one of them will
    # sit there unable to propose; should use separate accounts really.
    block_hashes = (deploy_and_propose(partitions[0][0], C[0], nonce=1),
                    deploy_and_propose(partitions[1][0], C[1], nonce=1))

    for partition, block_hash in zip(partitions, block_hashes):
        wait_for_block_hash_propagated_to_all_nodes(partition, block_hash)

    logging.info("CONNECT PARTITIONS")
    for connection in connections_between_partitions:
        logging.info("CONNECTING PARTITIONS: {}".format(connection))
        four_nodes_network.connect(connection)

    logging.info("PARTITIONS CONNECTED")

    # Node updates its list of alive peers in background with a certain period
    # So we need to wait here for nodes to re-connect partitioned peers
    for node in nodes:
        wait_for_peers_count_exactly(node, n - 1, 60)

    # When we propose a node in partition[0] it should propagate to partition[1],
    # however, nodes in partition[0] will still not see blocks from partition[1]
    # until they also propose a new one on top of the block the created during
    # the network outage.
    block_hash = deploy_and_propose(nodes[0], C[2], nonce=2)

    for partition, old_hash in zip(partitions, block_hashes):
        logging.info(f"CHECK {partition} HAS ALL BLOCKS CREATED IN BOTH PARTITIONS")
        wait_for_block_hashes_propagated_to_all_nodes(partition, [old_hash, block_hash])
