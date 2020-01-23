import logging
import json
import os
import re
import shutil
import tempfile
from pathlib import Path
from typing import List, Tuple, Dict, Union, Optional
import requests

from casperlabs_local_net.common import MAX_PAYMENT_ABI, Contract, EMPTY_ETC_CASPERLABS
from casperlabs_local_net.docker_base import LoggingDockerBase
from casperlabs_local_net.docker_client import DockerClient
from casperlabs_local_net.python_client import PythonClient
from casperlabs_local_net.errors import CasperLabsNodeAddressNotFoundError
from casperlabs_local_net.docker_base import DockerConfig
from casperlabs_local_net.casperlabs_accounts import (
    is_valid_account,
    Account,
    GENESIS_ACCOUNT,
)
from casperlabs_local_net.graphql import GraphQL
from casperlabs_client import extract_common_name
from casperlabs_client.abi import ABI
from casperlabs_local_net import grpc_proxy
from casperlabs_local_net.grpc_proxy import KademliaInterceptor, GossipInterceptor

FIRST_VALIDATOR_ACCOUNT = 100


class DockerNode(LoggingDockerBase):
    """
    A CasperLabs Docker Node object
    """

    CL_NODE_BINARY = "/opt/docker/bin/bootstrap"
    CL_NODE_DIRECTORY = "/root/.casperlabs"
    CL_ETC_CASPERLABS_DIR = "/etc/casperlabs"
    CL_NODE_DEPLOY_DIR = f"{CL_NODE_DIRECTORY}/deploy"
    CL_CHAINSPEC_DIR = f"{CL_NODE_DIRECTORY}/chainspec"
    CL_SOCKETS_DIR = f"{CL_NODE_DIRECTORY}/sockets"
    CL_BOOTSTRAP_DIR = f"{CL_NODE_DIRECTORY}/bootstrap"
    CL_ACCOUNTS_DIR = f"{CL_NODE_DIRECTORY}/accounts"

    # Used when node setup to use keys generated with client's keygen command
    CL_KEYS_DIR = f"{CL_NODE_DIRECTORY}/keys"
    CL_CASPER_GENESIS_ACCOUNT_PUBLIC_KEY_PATH = f"{CL_ACCOUNTS_DIR}/account-id-genesis"

    NUMBER_OF_BONDS = 10

    GRPC_SERVER_PORT = 40400
    GRPC_EXTERNAL_PORT = 40401
    GRPC_INTERNAL_PORT = 40402
    HTTP_PORT = 40403
    KADEMLIA_PORT = 40404
    PROXY_OFFSET = 5  # Proxy port offset added to normal port numbers
    DOCKER_PORT_MULTIPLIER = 10
    UNIQUE_RUN_MULTIPLIER = 100
    BEHIND_PROXY_OFFSET = 10000

    # Ports:
    # CasperLabs Docker Node requires 40400-40404.
    #
    # We started allocating ports based on digits.  However, we have run out of ports for the number of possible
    # nodes running at a given time.
    #
    # Ports use 0-4 in ones digit.  Proxy will add 5 to use 5-9.
    # Note: if we add more ports, we may have to manually specify the proxy ports needed, rather than a simple +5.
    #
    # Docker mapping to local adds 10 * node_number (self.config.number).  To allow for up to 10 nodes running.
    #
    #
    #
    #

    DOCKER_CLIENT = "d"
    PYTHON_CLIENT = "p"

    def __init__(self, cl_network, config: DockerConfig):
        self.cl_network = cl_network
        self.config = config
        self.p_client = PythonClient(self)
        self.d_client = DockerClient(self)
        super().__init__(config)
        self.graphql = GraphQL(self)

        self.proxy_server = None
        self.proxy_kademlia = None
        if config.behind_proxy:
            # Set up proxy of incoming connections: this node is server, the other one client.
            self.server_certificate_path = config.tls_certificate_local_path()
            self.server_key_path = config.tls_key_local_path()
            self.set_proxy_server()
            self.set_kademlia_proxy()
        self._client = self.DOCKER_CLIENT
        self.join_client_network()

    def set_proxy_server(self, interceptor_class=GossipInterceptor):
        if not self.config.behind_proxy:
            raise Exception("You must set up node with proxy first")
        if self.proxy_server:
            raise Exception("You must call this method only once")
        self.proxy_server = grpc_proxy.proxy_server(
            self,
            node_port=self.grpc_server_docker_port,
            node_host=self.node_host,
            proxy_port=self.server_proxy_port,
            server_certificate_file=self.server_certificate_path,
            server_key_file=self.server_key_path,
            client_certificate_file=self.server_certificate_path,
            client_key_file=self.server_key_path,
            interceptor_class=interceptor_class,
        )

    def set_kademlia_proxy(self, interceptor_class=KademliaInterceptor):
        if not self.config.behind_proxy:
            raise Exception("You must set up node with proxy first")
        if self.proxy_kademlia:
            raise Exception("You must call this method only once")
        self.proxy_kademlia = grpc_proxy.proxy_kademlia(
            self,
            node_port=self.kademlia_docker_port,
            node_host=self.node_host,
            proxy_port=self.kademlia_proxy_port,
            interceptor_class=interceptor_class,
        )

    @property
    def unique_run_offset(self) -> int:
        return self.config.unique_run_num * self.UNIQUE_RUN_MULTIPLIER

    @property
    def node_host(self):
        return os.environ.get("TAG_NAME") and self.container_name or "172.17.0.1"

    @property
    def proxy_host(self):
        return (
            os.environ.get("TAG_NAME")
            and f"test-{os.environ.get('TAG_NAME')}"
            or "172.17.0.1"
        )

    @property
    def server_proxy_port(self) -> int:
        return self.GRPC_SERVER_PORT + self.PROXY_OFFSET + self.docker_port_offset

    @property
    def kademlia_proxy_port(self) -> int:
        return self.KADEMLIA_PORT + self.PROXY_OFFSET + self.docker_port_offset

    @property
    def docker_port_offset(self) -> int:
        port = self.unique_run_offset
        if not self.is_in_docker:
            port += self.config.number * self.DOCKER_PORT_MULTIPLIER
        return port

    @property
    def grpc_server_docker_port(self) -> int:
        n = self.GRPC_SERVER_PORT + self.docker_port_offset
        if self.config.behind_proxy:
            return n + self.BEHIND_PROXY_OFFSET  # 50400 + self.docker_port_offset
        return n

    @property
    def kademlia_docker_port(self) -> int:
        n = self.KADEMLIA_PORT + self.docker_port_offset
        if self.config.behind_proxy:
            return n + self.BEHIND_PROXY_OFFSET  # 50404 + self.docker_port_offset
        return n

    @property
    def grpc_external_docker_port(self) -> int:
        return self.GRPC_EXTERNAL_PORT + self.docker_port_offset

    @property
    def grpc_internal_docker_port(self) -> int:
        return self.GRPC_INTERNAL_PORT + self.docker_port_offset

    @property
    def http_port(self) -> int:
        return self.HTTP_PORT + self.docker_port_offset

    @property
    def resources_folder(self) -> Path:
        """ This will return the resources folder that is copied into the correct location for testing """
        cur_path = Path(os.path.realpath(__file__)).parent
        return cur_path.parent / "resources"

    @property
    def timeout(self):
        """
        Number of seconds for a node to timeout.
        :return: int (seconds)
        """
        return self.config.command_timeout

    @property
    def container_type(self):
        return "node"

    @property
    def client(self) -> Union[DockerClient, PythonClient]:
        return {self.DOCKER_CLIENT: self.d_client, self.PYTHON_CLIENT: self.p_client}[
            self._client
        ]

    def use_python_client(self):
        self._client = self.PYTHON_CLIENT

    def use_docker_client(self):
        self._client = self.DOCKER_CLIENT

    @property
    def client_network_name(self) -> str:
        """
        This renders the network name that the docker client should have opened for Python Client connection
        """
        # Networks are created in docker_run_tests.sh.  Must stay in sync.
        return f"cl-{self.docker_tag}-{self.config.number}"

    def join_client_network(self) -> None:
        """
        Joins DockerNode to client network to enable Python Client communication
        """
        if os.environ.get("TAG_NAME"):
            # We are running in docker, because we have this environment variable
            self.connect_to_network(self.client_network_name)
            logging.info(
                f"Joining {self.container_name} to {self.client_network_name}."
            )

    @property
    def docker_ports(self) -> Dict[str, int]:
        """
        Generates a dictionary for docker port mapping.

        :return: dict for use in docker container run to open ports based on node number
        """
        ports = (
            (
                self.GRPC_SERVER_PORT + (self.config.behind_proxy and 10000 or 0),
                self.grpc_server_docker_port,
            ),
            (
                self.KADEMLIA_PORT + (self.config.behind_proxy and 10000 or 0),
                self.kademlia_docker_port,
            ),
            (self.GRPC_INTERNAL_PORT, self.grpc_internal_docker_port),
            (self.GRPC_EXTERNAL_PORT, self.grpc_external_docker_port),
            (self.HTTP_PORT, self.http_port),
        )
        port_dict = {f"{int_port}/tcp": ext_port for int_port, ext_port in ports}
        return port_dict

    @property
    def volumes(self) -> dict:
        d = {
            self.host_etc_casperlabs_dir: {
                "bind": self.CL_ETC_CASPERLABS_DIR,
                "mode": "rw",
            },
            self.host_keys_dir: {"bind": self.CL_KEYS_DIR, "mode": "rw"},
            self.host_chainspec_dir: {"bind": self.CL_CHAINSPEC_DIR, "mode": "rw"},
            self.host_bootstrap_dir: {"bind": self.CL_BOOTSTRAP_DIR, "mode": "rw"},
            self.host_accounts_dir: {"bind": self.CL_ACCOUNTS_DIR, "mode": "rw"},
            self.deploy_dir: {"bind": self.CL_NODE_DEPLOY_DIR, "mode": "rw"},
            self.config.socket_volume: {"bind": self.CL_SOCKETS_DIR, "mode": "rw"},
        }
        for k in d:
            logging.info(f"================ VOLUME: {k} => {d[k]}")
        return d

    def _get_container(self):
        self.deploy_dir = tempfile.mkdtemp(dir="/tmp", prefix="deploy_")
        self.create_resources_dir()

        commands = self.container_command
        logging.info(f"{self.container_name} commands: {commands}")

        # Locally, we use tcp ports.  In docker, we used docker networks
        ports = {}
        if not self.is_in_docker:
            ports = self.docker_ports
        logging.info(f"{self.container_name} ports: {ports}")

        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            auto_remove=False,
            detach=True,
            mem_limit=self.config.mem_limit,
            ports=ports,  # Exposing grpc for Python Client
            network=self.config.network,
            volumes=self.volumes,
            command=commands,
            hostname=self.container_name,
            environment=self.config.node_env,
        )
        return container

    def create_resources_dir(self) -> None:
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        shutil.copytree(str(self.resources_folder), self.host_mount_dir)
        # Creating a file where the node is expecting to see overrides, i.e. at ~/.casperlabs/chainspec/genesis
        self.create_genesis_accounts_file(
            f"{self.host_chainspec_dir}/genesis/accounts.csv"
        )
        if self.config.etc_casperlabs_directory != EMPTY_ETC_CASPERLABS:
            etc_casperlabs_chainspec = os.path.join(
                self.host_mount_dir, self.config.etc_casperlabs_directory, "chainspec"
            )
            accounts_file = f"{etc_casperlabs_chainspec}/genesis/accounts.csv"
            self.create_genesis_accounts_file(accounts_file)
            logging.info(f"======= CREATED accounts file in: {accounts_file}")

        if self.config.keys_directory:
            os.mkdir(self.host_keys_dir)
            cli = self.config.cli_class(self)
            output = cli("keygen", self.host_keys_dir)
            logging.info(f"keygen => {output}")

    # TODO: Should be changed to using validator-id from accounts
    def create_genesis_accounts_file(self, path: str = None) -> None:
        bond_amount = self.config.bond_amount
        N = self.NUMBER_OF_BONDS
        try:
            os.makedirs(os.path.dirname(path))
        except OSError:
            pass

        with open(path, "w") as f:
            # Give the initial motes to the genesis account, so that tests which use
            # this way of creating accounts work. But the accounts could be just
            # created this way, without having to do a transfer.
            f.write(f"{GENESIS_ACCOUNT.public_key},{self.cl_network.initial_motes},0\n")
            for i, pair in enumerate(
                Account(i)
                for i in range(FIRST_VALIDATOR_ACCOUNT, FIRST_VALIDATOR_ACCOUNT + N)
            ):
                bond = bond_amount(i, N)
                f.write(f"{pair.public_key},0,{bond}\n")

    def cleanup(self):
        super().cleanup()
        if self.proxy_server:
            self.proxy_server.stop()
        if self.proxy_kademlia:
            self.proxy_kademlia.stop()
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        if os.path.exists(self.deploy_dir):
            shutil.rmtree(self.deploy_dir)

    @property
    def genesis_account(self):
        return self.cl_network.genesis_account

    @property
    def test_account(self) -> Account:
        return self.cl_network.test_account(self)

    @property
    def from_address(self) -> str:
        return self.cl_network.from_address(self)

    @property
    def container_command(self):
        bootstrap_flag = "-s" if self.config.is_bootstrap else ""
        options = [
            f"{opt} {arg}"
            for opt, arg in self.config.node_command_options(
                self, self.container_name
            ).items()
        ]
        return f"run {bootstrap_flag} {' '.join(options)}"

    @property
    def metrics_url(self) -> str:
        server_name = self.container_name if self.is_in_docker else "localhost"
        return f"http://{server_name}:{self.http_port}/metrics"

    def get_metrics(self) -> Tuple[int, str]:
        try:
            return 0, self.get_metrics_strict()
        except Exception as e:
            return -1, str(e)

    def get_metrics_strict(self):
        response = requests.get(self.metrics_url)
        return response.text

    def transfer_to_account(
        self,
        to_account_id: int,
        amount: int,
        from_account_id: Union[str, int] = "genesis",
        session_contract: str = Contract.TRANSFER_TO_ACCOUNT,
        payment_contract: str = Contract.STANDARD_PAYMENT,
        payment_args: bytes = MAX_PAYMENT_ABI,
        gas_price: int = 1,
        is_deploy_error_check: bool = True,
    ) -> str:
        """
        Performs a transfer using the from account if given (or genesis if not)

        :param to_account_id: 1-20 index of test account for transfer into
        :param amount: amount of motes to transfer (mote = smallest unit of token)
        :param from_account_id: default 'genesis' account, but previously funded account_id is also valid.
        :param session_contract: session contract to execute.
        :param payment_contract: Payment contract to execute.
        :param payment_args: Payment Amount ABI
        :param gas_price: Gas price
        :param is_deploy_error_check: Check that amount transfer is success.

        :returns block_hash in hex str
        """
        logging.info(f"=== Transferring {amount} to {to_account_id}")

        assert (
            is_valid_account(to_account_id) and to_account_id != "genesis"
        ), "Can transfer only to non-genesis accounts in test framework (1-20)."
        assert is_valid_account(
            from_account_id
        ), "Must transfer from a valid account_id: 1-20 or 'genesis'"

        from_account = Account(from_account_id)
        to_account = Account(to_account_id)

        session_args = ABI.args(
            [
                ABI.account("account", to_account.public_key_binary),
                ABI.u64("amount", amount),
            ]
        )

        response, deploy_hash_bytes = self.p_client.deploy(
            from_address=from_account.public_key_hex,
            session_contract=session_contract,
            payment_contract=payment_contract,
            public_key=from_account.public_key_path,
            private_key=from_account.private_key_path,
            gas_price=gas_price,
            session_args=session_args,
            payment_args=payment_args,
        )

        deploy_hash_hex = deploy_hash_bytes.hex()
        assert len(deploy_hash_hex) == 64

        response = self.p_client.propose()

        block_hash = response.block_hash.hex()
        assert len(deploy_hash_hex) == 64

        if is_deploy_error_check:
            for deploy_info in self.p_client.show_deploys(block_hash):
                if deploy_info.is_error:
                    raise Exception(f"transfer_to_account: {deploy_info.error_message}")

        return block_hash

    def bond(
        self,
        session_contract: str,
        amount: int,
        from_account_id: Union[str, int] = "genesis",
    ) -> str:
        # NOTE: The Scala client is bundled with a bond contract that expects long_value,
        #       but the integration test version expects int.
        json_args = json.dumps([{"name": "amount", "value": {"int_value": amount}}])
        return self._deploy_and_propose_with_abi_args(
            session_contract, Account(from_account_id), json_args
        )

    def unbond(
        self,
        session_contract: str,
        maybe_amount: Optional[int] = None,
        from_account_id: Union[str, int] = "genesis",
    ) -> str:
        # NOTE: The Scala client is bundled with an unbond contract that expects an optional
        #       value, but the integration tests have their own version which expects an int
        #       and turns 0 into None inside the contract itself
        # amount = {} if maybe_amount is None else {"int_value": maybe_amount}
        # json_args = json.dumps(
        #     [{"name": "amount", "value": {"optional_value": amount}}]
        # )
        json_args = json.dumps(
            [{"name": "amount", "value": {"int_value": maybe_amount or 0}}]
        )
        return self._deploy_and_propose_with_abi_args(
            session_contract, Account(from_account_id), json_args
        )

    def _deploy_and_propose_with_abi_args(
        self,
        session_contract: str,
        from_account: Account,
        json_args: str,
        gas_price: int = 1,
    ) -> str:

        response, deploy_hash_bytes = self.p_client.deploy(
            from_address=from_account.public_key_hex,
            session_contract=session_contract,
            gas_price=gas_price,
            public_key=from_account.public_key_path,
            private_key=from_account.private_key_path,
            session_args=self.p_client.abi.args_from_json(json_args),
        )

        response = self.p_client.propose()

        block_hash = response.block_hash.hex()
        return block_hash

    def transfer_to_accounts(self, account_value_list) -> List[str]:
        """
        Allows batching calls to self.transfer_to_account to process in order.

        If from is not provided, the genesis account is used.

        :param account_value_list: List of [to_account_id, amount, Optional(from_account_id)]
        :return: List of block_hashes from transfer blocks.
        """
        block_hashes = []
        for avl in account_value_list:
            assert (
                2 <= len(avl) <= 3
            ), "Expected account_value_list as list of (to_account_id, value, Optional(from_account_id))"
            to_addr_id, amount = avl[:2]
            from_addr_id = "genesis" if len(avl) == 2 else avl[2]
            block_hashes.append(
                self.transfer_to_account(to_addr_id, amount, from_addr_id)
            )
        return block_hashes

    def show_blocks(self) -> Tuple[int, str]:
        return self.exec_run(f"{self.CL_NODE_BINARY} show-blocks")

    @property
    def node_id(self) -> str:
        return extract_common_name(self.config.tls_certificate_local_path())

    @property
    def address(self) -> str:
        if self.config.behind_proxy:
            protocol_port = self.server_proxy_port
            discovery_port = self.kademlia_proxy_port
            addr = f"casperlabs://{self.node_id}@{self.proxy_host}?protocol={protocol_port}&discovery={discovery_port}"
            logging.info(f"Address of the proxy: {addr}")
            return addr

        m = re.search(
            f"Listening for traffic on peer=(casperlabs://.+@{self.container.name}\\?protocol=\\d+&discovery=\\d+)\\.$",
            self.logs(),
            re.MULTILINE | re.DOTALL,
        )
        if m is None:
            raise CasperLabsNodeAddressNotFoundError()
        address = m.group(1)
        return address
