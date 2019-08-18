import logging
import os
import threading
from test.cl_node.casperlabs_node import CasperLabsNode
from test.cl_node.common import random_string, MAX_PAYMENT_COST
from test.cl_node.docker_base import DockerConfig
from test.cl_node.docker_execution_engine import DockerExecutionEngine
from test.cl_node.docker_node import DockerNode
from test.cl_node.log_watcher import GoodbyeInLogLine, wait_for_log_watcher
from test.cl_node.nonce_registry import NonceRegistry
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS  # TODO: remove
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT, Account
from test.cl_node.wait import (
    wait_for_block_hash_propagated_to_all_nodes,
    wait_for_approved_block_received_handler_state,
    wait_for_node_started,
    wait_for_peers_count_at_least,
)
from typing import Callable, Dict, List
from docker import DockerClient
from docker.errors import NotFound
import inspect


def test_name():
    for f in inspect.stack():
        if f.function not in ("test_name", "test_account") and (
            "_test_" in f.function or f.function.startswith("test_")
        ):
            return f.function


class CasperLabsNetwork:
    """
    CasperLabsNetwork is the base object for a network of 0-many CasperLabNodes.

    A subclass should implement `_create_cl_network` to stand up the type of network it needs.

    Convention is naming the bootstrap as number 0 and all others increment from that point.
    """

    def __init__(self, docker_client: DockerClient, extra_docker_params: Dict = None):
        self.extra_docker_params = extra_docker_params or {}
        self._next_key_number = 0
        self.docker_client = docker_client
        self.cl_nodes: List[CasperLabsNode] = []
        self._created_networks: List[str] = []
        NonceRegistry.reset()
        self._lock = (
            threading.RLock()
        )  # protect self.cl_nodes and self._created_networks
        self._accounts_lock = threading.Lock()
        self.test_accounts = {}
        self.next_key = 1

    @property
    def node_count(self) -> int:
        return len(self.cl_nodes)

    @property
    def docker_nodes(self) -> List[DockerNode]:
        with self._lock:
            return [cl_node.node for cl_node in self.cl_nodes]

    @property
    def execution_engines(self) -> List[DockerExecutionEngine]:
        with self._lock:
            return [cl_node.execution_engine for cl_node in self.cl_nodes]

    @property
    def genesis_account(self):
        """ Genesis Account Address """
        return GENESIS_ACCOUNT

    def test_account(self, node, amount=1000000) -> str:
        name = test_name()
        if not name:
            # This happens when a thread tries to deploy.
            # Name of the test that spawned the thread does not appear on the inspect.stack.
            # Threads that don't want to use genesis account
            # should pass from_address, public_key and private_key to deploy explicitly.
            return self.genesis_account
        elif name not in self.test_accounts:
            with self._accounts_lock:
                self.test_accounts[name] = Account(self.next_key)
                logging.info(
                    f"=== Creating test account #{self.next_key} {self.test_accounts[name].public_key_hex} for {name} "
                )
                block_hash = node.transfer_to_account(self.next_key, amount)
                # Waiting for the block with transaction that created new account to propagate to all nodes.
                # Expensive, but some tests may rely on it.
                wait_for_block_hash_propagated_to_all_nodes(
                    node.cl_network.docker_nodes, block_hash
                )
                for deploy in node.client.show_deploys(block_hash):
                    assert (
                        deploy.is_error is False
                    ), f"Account creation failed: {deploy}"
                self.next_key += 1
        return self.test_accounts[name]

    def from_address(self, node) -> str:
        return self.test_account(node).public_key_hex

    def get_key(self):
        key_pair = PREGENERATED_KEYPAIRS[self._next_key_number]
        self._next_key_number += 1
        return key_pair

    def create_cl_network(self, **kwargs):
        """
        Should be implemented with each network class to setup custom nodes and networks.
        """
        raise NotImplementedError("Must implement '_create_network' in subclass.")

    def create_docker_network(self) -> str:
        with self._lock:
            tag_name = os.environ.get("TAG_NAME") or "test"
            network_name = f"casperlabs_{random_string(5)}_{tag_name}"
            self._created_networks.append(network_name)
            self.docker_client.networks.create(network_name, driver="bridge")
            logging.info(f"Docker network {network_name} created.")
            return network_name

    def _add_cl_node(self, config: DockerConfig) -> None:
        with self._lock:
            config.number = self.node_count
            cl_node = CasperLabsNode(self, config)
            self.cl_nodes.append(cl_node)

    def add_new_node_to_network(self) -> None:
        kp = self.get_key()
        config = DockerConfig(self.docker_client, node_private_key=kp.private_key)
        self.add_cl_node(config)
        self.wait_method(wait_for_approved_block_received_handler_state, 1)
        self.wait_for_peers()

    def add_bootstrap(self, config: DockerConfig) -> None:
        if self.node_count > 0:
            raise Exception("There can be only one bootstrap")
        config.is_bootstrap = True
        self._add_cl_node(config)
        self.wait_method(wait_for_node_started, 0)

    def add_cl_node(
        self, config: DockerConfig, network_with_bootstrap: bool = True
    ) -> None:
        with self._lock:
            if self.node_count == 0:
                raise Exception("Must create bootstrap first")
            config.bootstrap_address = self.cl_nodes[0].node.address
            if network_with_bootstrap:
                config.network = self.cl_nodes[0].node.network
            self._add_cl_node(config)

    def stop_cl_node(self, node_number: int) -> None:
        with self._lock:
            cl_node = self.cl_nodes[node_number]

        cl_node.execution_engine.stop()
        node = cl_node.node
        with wait_for_log_watcher(GoodbyeInLogLine(node.container)):
            node.stop()

    def start_cl_node(self, node_number: int) -> None:
        with self._lock:
            self.cl_nodes[node_number].execution_engine.start()
            node = self.cl_nodes[node_number].node
            node.truncate_logs()
            node.start()
            wait_for_approved_block_received_handler_state(
                node, node.config.command_timeout
            )

    def wait_for_peers(self) -> None:
        if self.node_count < 2:
            return
        for cl_node in self.cl_nodes:
            wait_for_peers_count_at_least(
                cl_node.node, self.node_count - 1, cl_node.config.command_timeout
            )

    def wait_method(self, method: Callable, node_number: int) -> None:
        """
        Calls a wait method with the node and timeout from internal objects

        Blocks until satisfied or timeout.

        :param method: wait method to call
        :param node_number: index of self.cl_nodes to use as node
        :return: None
        """
        cl_node = self.cl_nodes[node_number]
        method(cl_node.node, cl_node.config.command_timeout)

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception_value, traceback=None):
        if exception_type is not None:
            import traceback as tb

            logging.error(
                f"Python Exception Occurred: {exception_type} {exception_value} {tb.format_exc()}"
            )

        with self._lock:
            for node in self.cl_nodes:
                node.cleanup()
            self.cleanup()

        return True

    def cleanup(self):
        with self._lock:
            for network_name in self._created_networks:
                try:
                    self.docker_client.networks.get(network_name).remove()
                except (NotFound, Exception) as e:
                    logging.warning(
                        f"Exception in cleanup while trying to remove network {network_name}: {str(e)}"
                    )


class OneNodeNetwork(CasperLabsNetwork):
    """ A single node network with just a bootstrap """

    is_payment_code_enabled = False
    initial_motes = MAX_PAYMENT_COST * 100  # 10 millions * 100 = 1 billion motes

    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            is_payment_code_enabled=self.is_payment_code_enabled,
            initial_motes=self.initial_motes,
        )
        self.add_bootstrap(config)


class PaymentNodeNetwork(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    is_payment_code_enabled = True


class TrillionPaymentNodeNetwork(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    is_payment_code_enabled = True
    initial_motes = (
        MAX_PAYMENT_COST * 100 * 1000
    )  # 10 millions * 100 * 1000 =  billion motes * 1000 = trillion


class PaymentNodeNetworkWithNoMinBalance(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    is_payment_code_enabled = True
    initial_motes = 10 ** 3


class PaymentNodForOnlyPaymentContract(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    is_payment_code_enabled = True
    # enough to run payment, but not enough to run session
    initial_motes = 3093878


class TwoNodeNetwork(CasperLabsNetwork):
    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
        )
        self.add_bootstrap(config)

        self.add_new_node_to_network()


class ThreeNodeNetwork(CasperLabsNetwork):
    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
        )
        self.add_bootstrap(config)

        for _ in range(1, 3):
            kp = self.get_key()
            config = DockerConfig(self.docker_client, node_private_key=kp.private_key)
            self.add_cl_node(config)

        for node_number in range(1, 3):
            self.wait_method(
                wait_for_approved_block_received_handler_state, node_number
            )
        self.wait_for_peers()


class MultiNodeJoinedNetwork(CasperLabsNetwork):
    def create_cl_network(self, node_count=10):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
        )
        self.add_bootstrap(config)

        for _ in range(1, node_count):
            kp = self.get_key()
            config = DockerConfig(self.docker_client, node_private_key=kp.private_key)
            self.add_cl_node(config)

        for node_number in range(1, node_count):
            self.wait_method(
                wait_for_approved_block_received_handler_state, node_number
            )
        self.wait_for_peers()


class CustomConnectionNetwork(CasperLabsNetwork):
    def create_cl_network(
        self, node_count: int = 3, network_connections: List[List[int]] = None
    ) -> None:
        """
        Allow creation of a network where all nodes are not connected to node-0's network and therefore each other.

        Ex:  Star Network

                     node3
                       |
            node1 -- node0 -- node2

            create_cl_network(node_count=4, network_connections=[[0, 1], [0, 2], [0, 3]])

        :param node_count: Number of nodes to create.
        :param network_connections: A list of lists of node indexes that should be joined.
        """
        self.network_names = {}
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
        )
        self.add_bootstrap(config)

        for i in range(1, node_count):
            kp = self.get_key()
            config = DockerConfig(
                self.docker_client,
                node_private_key=kp.private_key,
                network=self.create_docker_network(),
            )
            self.add_cl_node(config, network_with_bootstrap=False)

        for network_members in network_connections:
            self.connect(network_members)

        for node_number in range(1, node_count):
            self.wait_method(
                wait_for_approved_block_received_handler_state, node_number
            )

        # Calculate how many peers should be connected to each node.
        # Assumes duplicate network connections are not given, else wait_for_peers will time out.
        peer_counts = [0] * node_count
        for network in network_connections:
            for node_num in network:
                peer_counts[node_num] += len(network) - 1

        timeout = self.docker_nodes[0].timeout
        for node_num, peer_count in enumerate(peer_counts):
            if peer_count > 0:
                wait_for_peers_count_at_least(
                    self.docker_nodes[node_num], peer_count, timeout
                )

    def connect(self, network_members):
        # TODO: We should probably merge CustomConnectionNetwork with CasperLabsNetwork,
        # move its functionality into CasperLabsNetwork.
        with self._lock:
            network_name = self.create_docker_network()

            if network_name not in self.network_names:
                self.network_names[tuple(network_members)] = network_name
                for node_num in network_members:
                    self.docker_nodes[node_num].connect_to_network(network_name)

    def disconnect(self, connection):
        with self._lock:
            network_name = self.network_names[tuple(connection)]
            for node_num in connection:
                self.docker_nodes[node_num].disconnect_from_network(network_name)
            del self.network_names[tuple(connection)]


if __name__ == "__main__":
    # For testing adding new networks.
    import sys
    import time
    import docker

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    handler.setFormatter(formatter)
    root.addHandler(handler)

    # Just testing standing them up.
    # with OneNodeNetwork(docker.from_env()) as onn:
    #     pass

    with MultiNodeJoinedNetwork(docker.from_env()) as net:
        net.create_cl_network(10)
        time.sleep(10)
