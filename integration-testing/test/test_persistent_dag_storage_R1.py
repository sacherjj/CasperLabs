from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import (
    wait_for_connected_to_node,
    wait_for_finalised_hash,
    wait_for_metrics_and_assert_block_count,
    wait_for_block_hashes_propagated_to_all_nodes,
)
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT


def test_persistent_dag_storage(two_node_network):
    """
    Feature file: storage.feature
    Scenario: Stop a node in network, and restart it. Assert that it downloads only latest block not the whole DAG.
    """
    node0, node1 = two_node_network.docker_nodes
    for node in two_node_network.docker_nodes:
        node.d_client.deploy_and_propose(session_contract=Contract.HELLO_NAME_DEFINE)

    two_node_network.stop_cl_node(1)
    two_node_network.start_cl_node(1)

    timeout = node0.config.command_timeout

    wait_for_connected_to_node(node0, node1.name, timeout, 2)

    hash_string = node0.d_client.deploy_and_propose(
        session_contract=Contract.HELLO_NAME_DEFINE
    )

    wait_for_finalised_hash(node0, hash_string, timeout * 2)
    wait_for_finalised_hash(node1, hash_string, timeout * 2)

    number_of_blocks = 5
    wait_for_metrics_and_assert_block_count(node1, timeout, number_of_blocks)


def test_storage_after_multiple_node_deploy_propose_and_shutdown(two_node_network):
    """
    Feature file: storage.feature
    Scenario: Stop nodes and restart with correct dag and blockstorage
    """
    tnn = two_node_network
    node0, node1 = tnn.docker_nodes
    block_hashes = [
        node.d_client.deploy_and_propose(
            from_address=GENESIS_ACCOUNT.public_key_hex,
            public_key=GENESIS_ACCOUNT.public_key_path,
            private_key=GENESIS_ACCOUNT.private_key_path,
            session_contract=Contract.HELLO_NAME_DEFINE,
        )
        for node in (node0, node1)
    ]

    wait_for_block_hashes_propagated_to_all_nodes(tnn.docker_nodes, block_hashes)

    dag0 = node0.d_client.vdag(10)
    dag1 = node1.d_client.vdag(10)
    blocks0 = node0.d_client.show_blocks(10)
    blocks1 = node1.d_client.show_blocks(10)

    for node_num in range(2):
        tnn.stop_cl_node(node_num)
    for node_num in range(2):
        tnn.start_cl_node(node_num)

    wait_for_block_hashes_propagated_to_all_nodes(tnn.docker_nodes, block_hashes)

    assert dag0 == node0.d_client.vdag(10)
    assert dag1 == node1.d_client.vdag(10)
    assert blocks0 == node0.d_client.show_blocks(10)
    assert blocks1 == node1.d_client.show_blocks(10)
