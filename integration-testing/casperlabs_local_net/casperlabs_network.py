import inspect
import logging
import os
import threading
import errno
import shutil
from typing import Callable, Dict, List

from docker import DockerClient
from docker.errors import NotFound
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.desired_capabilities import DesiredCapabilities
from selenium.webdriver.remote.webdriver import WebDriver

from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT, Account
from casperlabs_local_net.casperlabs_node import CasperLabsNode
from casperlabs_local_net.common import (
    INITIAL_MOTES_AMOUNT,
    MAX_PAYMENT_COST,
    TEST_ACCOUNT_INITIAL_BALANCE,
    EMPTY_ETC_CASPERLABS,
    random_string,
)
from casperlabs_local_net.docker_config import DockerConfig, KeygenDockerConfig
from casperlabs_local_net.docker_clarity import (
    DockerClarity,
    DockerGrpcWebProxy,
    DockerSelenium,
)
from casperlabs_local_net.docker_config import DEFAULT_NODE_ENV
from casperlabs_local_net.docker_execution_engine import DockerExecutionEngine
from casperlabs_local_net.docker_node import FIRST_VALIDATOR_ACCOUNT, DockerNode
from casperlabs_local_net.log_watcher import GoodbyeInLogLine, wait_for_log_watcher
from casperlabs_local_net.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_block_hash_propagated_to_all_nodes,
    wait_for_clarity_started,
    wait_for_genesis_block,
    wait_for_node_started,
    wait_for_peers_count_at_least,
    wait_for_selenium_started,
)
from casperlabs_client import extract_common_name


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
    """

    grpc_encryption = False
    auto_propose = False
    behind_proxy = False
    initial_motes = INITIAL_MOTES_AMOUNT

    def __init__(self, docker_client: DockerClient, extra_docker_params: Dict = None):
        self.extra_docker_params = extra_docker_params or {}
        self._next_key_number = FIRST_VALIDATOR_ACCOUNT
        self.docker_client = docker_client
        self.cl_nodes: List[CasperLabsNode] = []
        self.clarity_node: DockerClarity = None
        self.selenium_node: DockerSelenium = None
        self.selenium_driver: WebDriver = None
        self.grpc_web_proxy_node: DockerGrpcWebProxy = None
        self._created_networks: List[str] = []
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

    @property
    def in_docker(self) -> bool:
        return os.getenv("IN_DOCKER") == "true"

    def lookup_node(self, node_id):
        m = {node.node_id: node for node in self.docker_nodes}
        return m[node_id]

    def test_account(self, node, amount=TEST_ACCOUNT_INITIAL_BALANCE) -> Account:
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
        key_pair = Account(self._next_key_number)
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

    def add_new_node_to_network(
        self, generate_config=None, account: Account = None
    ) -> Account:
        if account is None:
            account = self.get_key()

        if generate_config is not None:
            config = generate_config(account)
        else:
            config = DockerConfig(
                self.docker_client,
                node_private_key=account.private_key,
                node_account=account,
                grpc_encryption=self.grpc_encryption,
                auto_propose=self.auto_propose,
                behind_proxy=self.behind_proxy,
            )

        self.add_cl_node(config)
        self.wait_method(wait_for_approved_block_received_handler_state, 1)
        self.wait_for_peers()
        return account

    def add_bootstrap(
        self, config: DockerConfig, bootstrap_address: str = None
    ) -> None:
        config.is_bootstrap = True
        config.bootstrap_address = bootstrap_address
        self._add_cl_node(config)
        self.wait_method(wait_for_node_started, 0)
        wait_for_genesis_block(self.docker_nodes[0])

    def add_clarity(self, config: DockerConfig):
        if self.node_count < 1:
            raise Exception("There must be at lease one casperlabs node")
        with self._lock:
            node_name = self.cl_nodes[0].node.container_name
            self.grpc_web_proxy_node = DockerGrpcWebProxy(config, node_name)
            self.clarity_node = DockerClarity(
                config, self.grpc_web_proxy_node.container_name
            )
            self.selenium_node = DockerSelenium(config)
            wait_for_clarity_started(self.clarity_node, config.command_timeout, 1)
            # Since we need pull selenium images from docker hub, it will take more time
            wait_for_selenium_started(self.selenium_node, 5 * 60, 1)
            if self.in_docker:
                # If these integration tests are running in a docker container, then we need connect the docker container
                # to the network of selenium
                network = self.selenium_node.network_from_name(config.network)
                # Gets name of container name of integration_testing
                integration_test_node = os.getenv("HOSTNAME")
                network.connect(integration_test_node)
                remote_drive = f"http://{self.selenium_node.name}:4444/wd/hub"
            else:
                remote_drive = f"http://127.0.0.1:4444/wd/hub"
            chrome_options = Options()
            prefs = {"profile.default_content_setting_values.automatic_downloads": 1}
            chrome_options.add_experimental_option("prefs", prefs)
            self.selenium_driver = webdriver.Remote(
                remote_drive, DesiredCapabilities.CHROME, options=chrome_options
            )
            self.selenium_driver.implicitly_wait(30)

    def add_cl_node(
        self,
        config: DockerConfig,
        network_with_bootstrap: bool = True,
        bootstrap_address: str = None,
    ) -> None:
        with self._lock:
            if self.node_count == 0:
                raise Exception("Must create bootstrap first")
            config.bootstrap_address = (
                bootstrap_address or self.cl_nodes[0].node.address
            )
            if network_with_bootstrap:
                config.network = self.cl_nodes[0].node.config.network
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
            wait_for_genesis_block(self.docker_nodes[node_number])

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
            if self.clarity_node:
                self.clarity_node.cleanup()
            if self.grpc_web_proxy_node:
                self.grpc_web_proxy_node.cleanup()
            if self.selenium_node:
                self.selenium_node.cleanup()
            for node in self.cl_nodes:
                node.cleanup()
            if self.in_docker and self.selenium_node:
                # If these integration tests are running in a docker container,
                # then we need disconnect the docker container from the network of selenium
                network = self.selenium_node.network_from_name(
                    self.selenium_node.config.network
                )
                # Gets name of container name of integration_testing
                integration_test_node = os.getenv("HOSTNAME")
                network.disconnect(integration_test_node)

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

    grpc_encryption = False

    def create_cl_network(self):
        account = self.get_key()
        self.add_bootstrap(self.docker_config(account))
        wait_for_genesis_block(self.docker_nodes[0])

    def docker_config(self, account):
        config = DockerConfig(
            self.docker_client,
            node_private_key=account.private_key,
            node_public_key=account.public_key,
            network=self.create_docker_network(),
            initial_motes=self.initial_motes,
            node_account=account,
            grpc_encryption=self.grpc_encryption,
            auto_propose=self.auto_propose,
        )
        return config


class OneNodeNetworkWithChainspecUpgrades(OneNodeNetwork):
    THIS_DIRECTORY = os.path.dirname(os.path.realpath(__file__))
    RESOURCES = f"{THIS_DIRECTORY}/../resources/"
    EE_CONTRACTS_DIR = f"{THIS_DIRECTORY}/../../execution-engine/target/wasm32-unknown-unknown/release/"

    # We need to copy all required contracts in test chainspecs
    REQUIRED_CONTRACTS = (
        "mint_install.wasm",
        "modified_system_upgrader.wasm",
        "pos_install.wasm",
    )

    def __init__(
        self,
        docker_client: DockerClient,
        extra_docker_params: Dict = None,
        chainspec_directory: str = "test-chainspec",
        etc_casperlabs_directory: str = EMPTY_ETC_CASPERLABS,
    ):
        super().__init__(docker_client, extra_docker_params)
        self.chainspec_directory = chainspec_directory
        self.etc_casperlabs_directory = etc_casperlabs_directory
        self.etc_casperlabs_chainspec = os.path.join(
            self.RESOURCES, self.etc_casperlabs_directory, "chainspec"
        )

        source_directory = (
            os.environ.get("TAG_NAME")
            and "/root/system_contracts/"
            or self.EE_CONTRACTS_DIR
        )
        self.copy_system_contracts(
            source_directory, os.path.join(self.RESOURCES, self.chainspec_directory)
        )

        if self.etc_casperlabs_directory != EMPTY_ETC_CASPERLABS:
            self.copy_system_contracts(source_directory, self.etc_casperlabs_chainspec)

    def copy_system_contracts(self, source_directory, destination_base):
        for (_, subdirectories, _) in os.walk(destination_base):
            for subdirectory in subdirectories:
                destination_directory = os.path.join(destination_base, subdirectory)
                try:
                    os.makedirs(destination_directory)
                except OSError as e:
                    if e.errno != errno.EEXIST:
                        raise
                for file_name in self.REQUIRED_CONTRACTS:
                    shutil.copy(
                        os.path.join(source_directory, file_name), destination_directory
                    )

    def docker_config(self, account):
        config = super().docker_config(account)
        config.chainspec_directory = self.chainspec_directory
        config.etc_casperlabs_directory = self.etc_casperlabs_directory
        return config


class ReadOnlyNodeNetwork(OneNodeNetwork):
    is_payment_code_enabled = True

    def docker_config(self, account):
        config = super().docker_config(account)
        config.is_read_only = True
        return config


class PaymentNodeNetwork(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    pass


class TrillionPaymentNodeNetwork(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    initial_motes = (
        MAX_PAYMENT_COST * 100 * 1000
    )  # 10 millions * 100 * 1000 =  billion motes * 1000 = trillion


class PaymentNodeNetworkWithNoMinBalance(OneNodeNetwork):
    """ A single node network with payment code enabled"""

    initial_motes = 10 ** 3


class OneNodeWithGRPCEncryption(OneNodeNetwork):
    grpc_encryption = True


class OneNodeWithAutoPropose(OneNodeNetwork):
    auto_propose = True
    # TODO: enable encryption once asyncio client's gRPC encryption fixed
    # grpc_encryption = True


class OneNodeWithClarity(OneNodeNetwork):
    def create_cl_network(self):
        account = self.get_key()
        config = self.docker_config(account)
        # Enable auto proposing
        new_env = DEFAULT_NODE_ENV.copy()
        new_env["CL_CASPER_AUTO_PROPOSE_ENABLED"] = "true"
        config.node_env = new_env
        self.add_bootstrap(config)
        self.add_clarity(config)


class TwoNodeNetwork(CasperLabsNetwork):
    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
        )
        self.add_bootstrap(config)
        self.add_new_node_to_network()


class TwoNodeNetworkWithGeneratedKeys(TwoNodeNetwork):
    def __init__(self, docker_client, cli_class):
        super().__init__(docker_client)
        self.cli_class = cli_class

    def create_cl_network(self):
        kp = self.get_key()
        config = KeygenDockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
            keys_directory="keys",
            cli_class=self.cli_class,
        )
        self.add_bootstrap(config)
        self.add_new_node_to_network()


class TwoNodeWithDifferentAccountsCSVNetwork(CasperLabsNetwork):
    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
            command_timeout=30,
        )
        self.add_bootstrap(config)
        # Create accounts.csv of the second node with different bond amounts.
        self.add_new_node_to_network(
            (
                lambda kp: DockerConfig(
                    self.docker_client,
                    node_private_key=kp.private_key,
                    node_account=kp,
                    grpc_encryption=self.grpc_encryption,
                    behind_proxy=self.behind_proxy,
                    bond_amount=lambda i, n: n + 3 * i,
                )
            )
        )


class EncryptedTwoNodeNetwork(TwoNodeNetwork):
    grpc_encryption = True


class InterceptedOneNodeNetwork(OneNodeNetwork):
    grpc_encryption = True
    behind_proxy = True

    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
            behind_proxy=True,
        )
        self.add_bootstrap(config)


class InterceptedTwoNodeNetwork(TwoNodeNetwork):
    grpc_encryption = True
    behind_proxy = True

    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
            behind_proxy=True,
        )
        self.add_bootstrap(config)
        self.add_new_node_to_network(
            (
                lambda kp: DockerConfig(
                    self.docker_client,
                    node_private_key=kp.private_key,
                    node_public_key=kp.public_key,
                    network=self.create_docker_network(),
                    node_account=kp,
                    grpc_encryption=self.grpc_encryption,
                    behind_proxy=True,
                )
            )
        )


class ThreeNodeNetwork(CasperLabsNetwork):
    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
        )
        self.add_bootstrap(config)

        for _ in range(1, 3):
            kp = self.get_key()
            config = DockerConfig(
                self.docker_client, node_private_key=kp.private_key, node_account=kp
            )
            self.add_cl_node(config)

        for node_number in range(1, 3):
            self.wait_method(
                wait_for_approved_block_received_handler_state, node_number
            )
        self.wait_for_peers()


class ThreeNodeNetworkWithTwoBootstraps(CasperLabsNetwork):
    """
    A three nodes network where
    - node-0 is a standalone node, bootstrapping from node-1 if node-1 is available,
    - node-1 is a standalone node, bootstrapping from node-0 if node-0 is available,
    - node-2 is setup to bootstrap from node-0 and node-1.
    """

    def get_node_config(self, number, network):
        kp = self.get_key()
        return DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            node_account=kp,
            number=number,
            network=network,
        )

    def _docker_tag(self, config):
        return os.environ.get("TAG_NAME") or config.custom_docker_tag or "latest"

    def _node_address(self, config):
        node_id = extract_common_name(config.tls_certificate_local_path())
        return f"casperlabs://{node_id}@node-{config.number}-{config.rand_str}-{self._docker_tag(config)}?protocol=40400&discovery=40404"

    def create_cl_network(self):

        network = self.create_docker_network()
        node_0_config = self.get_node_config(0, network)
        node_1_config = self.get_node_config(1, network)

        node_0_bootstrap_address = self._node_address(node_1_config)
        node_1_bootstrap_address = self._node_address(node_0_config)

        self.add_bootstrap(node_0_config, bootstrap_address=node_0_bootstrap_address)
        self.add_cl_node(
            node_1_config,
            network_with_bootstrap=False,
            bootstrap_address=node_1_bootstrap_address,
        )

        for node in self.docker_nodes:
            wait_for_node_started(node, 30, 1)

        assert (
            self.docker_nodes[1].config.bootstrap_address
            == self.docker_nodes[0].address
        )
        assert (
            self.docker_nodes[0].config.bootstrap_address
            == self.docker_nodes[1].address
        )

        config = self.get_node_config(2, network)
        self.add_cl_node(
            config,
            bootstrap_address=f'"{self.cl_nodes[0].node.address} {self.cl_nodes[1].node.address}"',
        )

        for i in range(3):
            self.wait_method(wait_for_approved_block_received_handler_state, i)

        self.wait_for_peers()


class MultiNodeJoinedNetwork(CasperLabsNetwork):
    def create_cl_network(self, node_count=10):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
        )
        self.add_bootstrap(config)

        for _ in range(1, node_count):
            kp = self.get_key()
            config = DockerConfig(
                self.docker_client, node_private_key=kp.private_key, node_account=kp
            )
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
            node_account=kp,
        )
        self.add_bootstrap(config)

        for i in range(1, node_count):
            kp = self.get_key()
            config = DockerConfig(
                self.docker_client,
                node_private_key=kp.private_key,
                network=self.create_docker_network(),
                node_account=kp,
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


class NetworkWithTaggedDev(CasperLabsNetwork):
    def create_cl_network(self, node_count=2):
        kp = self.get_key()
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
        )
        self.add_bootstrap(config)
        config = DockerConfig(
            self.docker_client,
            node_private_key=kp.private_key,
            node_public_key=kp.public_key,
            network=self.create_docker_network(),
            node_account=kp,
            grpc_encryption=self.grpc_encryption,
            custom_docker_tag="dev",
        )
        self.add_cl_node(config)


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
