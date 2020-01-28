import pytest
from itertools import count
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes


def test_equivocating_node_shutdown_and_other_nodes_are_working_ok(three_node_network):
    network = three_node_network
    nodes = network.docker_nodes
    account = GENESIS_ACCOUNT

    deploy_options = dict(
        from_address=account.public_key_hex,
        public_key=account.public_key_path,
        private_key=account.private_key_path,
        session_contract=Contract.HELLO_NAME_DEFINE,
    )

    block_hash = nodes[0].p_client.deploy_and_propose(**deploy_options)
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Clear state of node-0 so when we restart it
    # it doesn't remember the block it created before.
    nodes[0].clear_state()

    # Stop node-0. Also, stop all other nodes so when node-0 restarts it cannot sync old
    # blocks from them.
    for i in range(len(nodes)):
        network.stop_cl_node(i)

    network.start_cl_node(0)
    # After node-0 restarted it should only have the genesis block.
    assert len(list(nodes[0].p_client.show_blocks(1000))) == 1

    # Create the equivocating block.
    block_hash = nodes[0].p_client.deploy_and_propose(**deploy_options)
    for i in range(1, len(nodes)):
        network.start_cl_node(i)
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    assert len(list(nodes[1].p_client.show_blocks(1000))) == 3

    block_hash = nodes[1].p_client.deploy_and_propose(**deploy_options)
    with pytest.raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Check node-0 is offline. If it terminated the docker container it was running in stopped as well.
    with pytest.raises(Exception) as excinfo:
        nodes[0].d_client.show_blocks(1)
    assert "Unable to resolve host node-0" in str(excinfo.value.output)

    assert "Node has detected it's own equivocation with block" in nodes[0].logs()

    # node-0 is out of action. The rest of the nodes have equivocated blocks in their DAGs.
    # Regardless, the remaining nodes should carry on working correctly.
    # They all should be able to deploy contracts and propose new blocks with them.

    working_nodes = nodes[1:]

    block_hash = working_nodes[0].p_client.deploy_and_propose(
        from_address=account.public_key_hex,
        public_key=account.public_key_path,
        private_key=account.private_key_path,
        session_contract=Contract.COUNTER_DEFINE,
    )
    wait_for_block_hash_propagated_to_all_nodes(working_nodes, block_hash)

    # Deploy counter_call.wasm on each node a couple of times,
    # check the value of the counter is correct.
    expected_counter_result = count(1)
    for i in range(2):
        for node in working_nodes:
            block_hash = node.p_client.deploy_and_propose(
                from_address=account.public_key_hex,
                public_key=account.public_key_path,
                private_key=account.private_key_path,
                session_contract=Contract.COUNTER_CALL,
            )
            wait_for_block_hash_propagated_to_all_nodes(working_nodes, block_hash)

            state = node.p_client.query_state(
                block_hash=block_hash,
                key=account.public_key_hex,
                key_type="address",
                path="counter/count",
            )
            assert state.int_value == next(expected_counter_result)
