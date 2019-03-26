import threading

import conftest
from casperlabsnode_testing.casperlabsnode import (
    Node,
    bootstrap_connected_peer,
    docker_network_with_started_bootstrap,
)
from casperlabsnode_testing.common import random_string
from casperlabsnode_testing.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from casperlabsnode_testing.wait import (
    wait_for_blocks_count_at_least,
    wait_for_peers_count_at_least,
)


class DeployThread(threading.Thread):
    def __init__(self, name: str, node: Node, contract: str, count: int) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.contract = contract
        self.count = count

    def run(self) -> None:
        for _ in range(self.count):
            self.node.deploy()
            self.node.propose()


BOOTSTRAP_NODE_KEYS = PREGENERATED_KEYPAIRS[0]
BONDED_VALIDATOR_KEY_1 = PREGENERATED_KEYPAIRS[1]
BONDED_VALIDATOR_KEY_2 = PREGENERATED_KEYPAIRS[2]
BONDED_VALIDATOR_KEY_3 = PREGENERATED_KEYPAIRS[3]


def create_volume(docker_client) -> str:
    volume_name = "casperlabs{}".format(random_string(5).lower())
    docker_client.volumes.create(name=volume_name, driver="local")
    return volume_name


def test_multiple_deploys_at_once(command_line_options_fixture, docker_client_fixture):
    contract_path = 'helloname.wasm'
    peers_keypairs = [BONDED_VALIDATOR_KEY_1, BONDED_VALIDATOR_KEY_2, BONDED_VALIDATOR_KEY_3]
    with conftest.testing_context(command_line_options_fixture, docker_client_fixture, bootstrap_keypair=BOOTSTRAP_NODE_KEYS, peers_keypairs=peers_keypairs) as context:
        with docker_network_with_started_bootstrap(context=context) as bootstrap_node:
            volume_name1 = create_volume(docker_client_fixture)
            kwargs = {'context': context, 'bootstrap': bootstrap_node}
            with bootstrap_connected_peer(name='bonded-validator-1', keypair=BONDED_VALIDATOR_KEY_1, socket_volume=volume_name1, **kwargs) as no1:
                volume_name2 = create_volume(docker_client_fixture)
                with bootstrap_connected_peer(name='bonded-validator-2', keypair=BONDED_VALIDATOR_KEY_2, socket_volume=volume_name2, **kwargs) as no2:
                    volume_name3 = create_volume(docker_client_fixture)
                    with bootstrap_connected_peer(name='bonded-validator-3', keypair=BONDED_VALIDATOR_KEY_3, socket_volume=volume_name3, **kwargs) as no3:
                        wait_for_peers_count_at_least(bootstrap_node, 3, context.node_startup_timeout)
                        deploy1 = DeployThread("node1", no1, contract_path, 1)
                        deploy1.start()
                        expected_blocks_count = 1
                        wait_for_blocks_count_at_least(
                            no1,
                            expected_blocks_count,
                            1,
                            context.node_startup_timeout
                        )
                        deploy2 = DeployThread("node2", no2, contract_path, 3)
                        deploy2.start()

                        deploy3 = DeployThread("node3", no3, contract_path, 3)
                        deploy3.start()

                        deploy1.join()
                        deploy2.join()
                        deploy3.join()

                        expected_blocks_count = 7
                        wait_for_blocks_count_at_least(
                            no1,
                            expected_blocks_count,
                            4,
                            context.node_startup_timeout
                        )
                        wait_for_blocks_count_at_least(
                            no2,
                            expected_blocks_count,
                            4,
                            context.node_startup_timeout
                        )
                        wait_for_blocks_count_at_least(
                            no3,
                            expected_blocks_count,
                            4,
                            context.node_startup_timeout
                        )

    for _volume in docker_client_fixture.volumes.list():
        if _volume.name.startswith("casperlabs"):
            _volume.remove(force=True)
