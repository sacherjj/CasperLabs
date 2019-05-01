import logging
from . import conftest
from .cl_node.casperlabsnode import (
    COUNTER_CALL,
    HELLO_WORLD,
    MAILING_LIST_CALL,
    COMBINED_CONTRACT,
    complete_network,
    deploy,
    propose,
    get_contract_state
)
from .cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from .cl_node.wait import (
    wait_for_count_the_blocks_on_node,
)


def test_call_contracts_one_another(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(
        command_line_options_fixture,
        docker_client_fixture,
        peers_keypairs=PREGENERATED_KEYPAIRS[1:2]  # will create a 3 node network
    ) as context:
        with complete_network(context) as network:
            deploy(network.bootstrap, COMBINED_CONTRACT)
            propose(network.bootstrap, COMBINED_CONTRACT)

            for node in network.nodes:
                wait_for_count_the_blocks_on_node(node, context.node_startup_timeout * 3, 1)
            generated_hashes = {}
            for contract_name in (COUNTER_CALL, MAILING_LIST_CALL, HELLO_WORLD):
                list_of_hashes = []
                for node in network.nodes:
                    deploy(node, contract_name)
                    block_hash = propose(node, contract_name)
                    list_of_hashes.append(block_hash)
                generated_hashes[contract_name] = list_of_hashes
            logging.info(generated_hashes)
            for index, counter_hash in enumerate(generated_hashes[COUNTER_CALL]):
                expected_result = index + 1
                output = get_contract_state(
                    docker_client=context.docker,
                    network_name=network.network,
                    target_host_name=network.nodes[index].name,
                    port=40401,
                    _type="address",
                    key=3030303030303030303030303030303030303030,
                    path="counter/count",
                    block_hash=counter_hash
                )
                assert  f"integer: {expected_result}" in str(output)
                logging.info(f"The output is :{output}")

            for index, mailing_list_hash in enumerate(generated_hashes[MAILING_LIST_CALL]):
                output = get_contract_state(
                    docker_client=context.docker,
                    network_name=network.network,
                    target_host_name=network.nodes[index].name,
                    port=40401,
                    _type="address",
                    key=3030303030303030303030303030303030303030,
                    path="mailing/list",
                    block_hash=mailing_list_hash
                )
                assert 'string_list {\\n  list: "CasperLabs"\\n}' in str(output)

                logging.info(f"The output is :{output}")

            for index, hello_world_hash in enumerate(generated_hashes[HELLO_WORLD]):
                output = get_contract_state(
                    docker_client=context.docker,
                    network_name=network.network,
                    target_host_name=network.nodes[index].name,
                    port=40401,
                    _type="address",
                    key=3030303030303030303030303030303030303030,
                    path="hello",
                    block_hash=hello_world_hash
                )
                logging.info(f"The output is :{output}")
