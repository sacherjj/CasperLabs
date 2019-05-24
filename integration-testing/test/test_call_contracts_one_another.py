from .cl_node.casperlabsnode import (
    COMBINED_CONTRACT,
    COUNTER_CALL,
    HELLO_WORLD,
    MAILING_LIST_CALL,
    get_contract_state,
)
from .cl_node.wait import wait_for_blocks_count_at_least


def test_call_contracts_one_another(three_node_network):
    tnn = three_node_network
    bootstrap, node1, node2 = tnn.docker_nodes
    bootstrap.deploy_and_propose(session_contract=COMBINED_CONTRACT, payment_contract=COMBINED_CONTRACT)
    for node in tnn.docker_nodes:
        wait_for_blocks_count_at_least(node, 1, 2, node.timeout)
    generated_hashes = {}
    for contract_name in (COUNTER_CALL, MAILING_LIST_CALL, HELLO_WORLD):
        list_of_hashes = []
        for node in tnn.docker_nodes:
            block_hash = node.deploy_and_propose(session_contract=contract_name,
                                                 payment_contract=contract_name)
            list_of_hashes.append(block_hash)
        generated_hashes[contract_name] = list_of_hashes
    for index, counter_hash in enumerate(generated_hashes[COUNTER_CALL]):
        expected_result = index + 1
        output = get_contract_state(
            docker_client=three_node_network.docker_client,
            network_name=tnn.docker_nodes[index].network,
            target_host_name=tnn.docker_nodes[index].name,
            port=40401,
            _type="address",
            key=3030303030303030303030303030303030303030303030303030303030303030,
            path="counter/count",
            block_hash=counter_hash
        )
        assert bytes(f'integer: {expected_result}\n\n', 'utf-8') == output

    for index, mailing_list_hash in enumerate(generated_hashes[MAILING_LIST_CALL]):
        output = get_contract_state(
            docker_client=three_node_network.docker_client,
            network_name=tnn.docker_nodes[index].network,
            target_host_name=tnn.docker_nodes[index].name,
            port=40401,
            _type="address",
            key=3030303030303030303030303030303030303030303030303030303030303030,
            path="mailing/list",
            block_hash=mailing_list_hash
        )
        assert bytes('string_list {\n  list: "CasperLabs"\n}\n\n', 'utf-8') == output

    for index, hello_world_hash in enumerate(generated_hashes[HELLO_WORLD]):
        output = get_contract_state(
            docker_client=three_node_network.docker_client,
            network_name=tnn.docker_nodes[index].network,
            target_host_name=tnn.docker_nodes[index].name,
            port=40401,
            _type="address",
            key=3030303030303030303030303030303030303030303030303030303030303030,
            path="helloworld",
            block_hash=hello_world_hash
        )
        assert b'string_val: "Hello, World"\n\n' == output
