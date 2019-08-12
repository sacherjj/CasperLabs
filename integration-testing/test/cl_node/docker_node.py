import logging
import json
import os
import re
import shutil
import tempfile
from pathlib import Path
from typing import List, Tuple, Dict, Union, Optional

from test.cl_node.common import extract_block_hash_from_propose_output
from test.cl_node.docker_base import LoggingDockerBase
from test.cl_node.docker_client import DockerClient
from test.cl_node.errors import CasperLabsNodeAddressNotFoundError
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from test.cl_node.python_client import PythonClient
from test.cl_node.docker_base import DockerConfig
from test.cl_node.casperlabs_accounts import is_valid_account, Account


class DockerNode(LoggingDockerBase):
    """
    A CasperLabs Docker Node object
    """

    CL_NODE_BINARY = "/opt/docker/bin/bootstrap"
    CL_NODE_DIRECTORY = "/root/.casperlabs"
    CL_NODE_DEPLOY_DIR = f"{CL_NODE_DIRECTORY}/deploy"
    CL_GENESIS_DIR = f"{CL_NODE_DIRECTORY}/genesis"
    CL_SOCKETS_DIR = f"{CL_NODE_DIRECTORY}/sockets"
    CL_BOOTSTRAP_DIR = f"{CL_NODE_DIRECTORY}/bootstrap"
    CL_ACCOUNTS_DIR = f"{CL_NODE_DIRECTORY}/accounts"
    CL_BONDS_FILE = f"{CL_GENESIS_DIR}/bonds.txt"
    CL_CASPER_GENESIS_ACCOUNT_PUBLIC_KEY_PATH = f"{CL_ACCOUNTS_DIR}/account-id-genesis"

    NUMBER_OF_BONDS = 10

    NETWORK_PORT = 40400
    GRPC_EXTERNAL_PORT = 40401
    GRPC_INTERNAL_PORT = 40402
    HTTP_PORT = 40403
    KADEMLIA_PORT = 40404

    DOCKER_CLIENT = "d"
    PYTHON_CLIENT = "p"

    def __init__(self, cl_network, config: DockerConfig, socket_volume: str):
        super().__init__(config, socket_volume)
        self.cl_network = cl_network
        self._client = self.DOCKER_CLIENT
        self.p_client = PythonClient(self)
        self.d_client = DockerClient(self)
        self.join_client_network()

    @property
    def docker_port_offset(self) -> int:
        if self.is_in_docker:
            return 0
        else:
            return self.number * 10

    @property
    def grpc_external_docker_port(self) -> int:
        return self.GRPC_EXTERNAL_PORT + self.docker_port_offset

    @property
    def grpc_internal_docker_port(self) -> int:
        return self.GRPC_INTERNAL_PORT + self.docker_port_offset

    @property
    def resources_folder(self) -> Path:
        """ This will return the resources folder that is copied into the correct location for testing """
        cur_path = Path(os.path.realpath(__file__)).parent
        while cur_path.name != "integration-testing":
            cur_path = cur_path.parent
        return cur_path / "resources"

    @property
    def timeout(self):
        """
        Number of seconds for a node to timeout.
        :return: int (seconds)
        """
        return self.config.command_timeout

    @property
    def number(self) -> int:
        return self.config.number

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
        return {
            f"{self.GRPC_INTERNAL_PORT}/tcp": self.grpc_internal_docker_port,
            f"{self.GRPC_EXTERNAL_PORT}/tcp": self.grpc_external_docker_port,
        }

    def _get_container(self):
        env = self.config.node_env.copy()
        env[
            "CL_CASPER_GENESIS_ACCOUNT_PUBLIC_KEY_PATH"
        ] = self.CL_CASPER_GENESIS_ACCOUNT_PUBLIC_KEY_PATH
        java_options = os.environ.get("_JAVA_OPTIONS")
        if java_options is not None:
            env["_JAVA_OPTIONS"] = java_options
        self.deploy_dir = tempfile.mkdtemp(dir="/tmp", prefix="deploy_")
        self.create_resources_dir()

        commands = self.container_command
        logging.info(f"{self.container_name} commands: {commands}")

        # Locally, we use tcp ports.  In docker, we used docker networks
        ports = {}
        if not self.is_in_docker:
            ports = self.docker_ports

        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            auto_remove=False,
            detach=True,
            mem_limit=self.config.mem_limit,
            ports=ports,  # Exposing grpc for Python Client
            network=self.network,
            volumes=self.volumes,
            command=commands,
            hostname=self.container_name,
            environment=env,
        )
        return container

    @property
    def network(self):
        return self.config.network

    def create_resources_dir(self) -> None:
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        shutil.copytree(str(self.resources_folder), self.host_mount_dir)
        self.create_bonds_file()

    # TODO: Should be changed to using validator-id from accounts
    def create_bonds_file(self) -> None:
        N = self.NUMBER_OF_BONDS
        path = f"{self.host_genesis_dir}/bonds.txt"
        os.makedirs(os.path.dirname(path))
        with open(path, "a") as f:
            for i, pair in enumerate(PREGENERATED_KEYPAIRS[:N]):
                bond = N + 2 * i
                f.write(f"{pair.public_key} {bond}\n")

    def cleanup(self):
        super().cleanup()
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        if os.path.exists(self.deploy_dir):
            shutil.rmtree(self.deploy_dir)

    @property
    def genesis_account(self):
        return self.cl_network.genesis_account

    @property
    def test_account(self) -> str:
        return self.cl_network.test_account(self)

    @property
    def from_address(self) -> str:
        return self.cl_network.from_address(self)

    @property
    def volumes(self) -> dict:
        if self.config.volumes is not None:
            return self.config.volumes

        return {
            self.host_genesis_dir: {"bind": self.CL_GENESIS_DIR, "mode": "rw"},
            self.host_bootstrap_dir: {"bind": self.CL_BOOTSTRAP_DIR, "mode": "rw"},
            self.host_accounts_dir: {"bind": self.CL_ACCOUNTS_DIR, "mode": "rw"},
            self.deploy_dir: {"bind": self.CL_NODE_DEPLOY_DIR, "mode": "rw"},
            self.socket_volume: {"bind": self.CL_SOCKETS_DIR, "mode": "rw"},
        }

    @property
    def container_command(self):
        bootstrap_flag = "-s" if self.config.is_bootstrap else ""
        options = [
            f"{opt} {arg}"
            for opt, arg in self.config.node_command_options(
                self.container_name
            ).items()
        ]
        return f"run {bootstrap_flag} {' '.join(options)}"

    def get_metrics(self) -> Tuple[int, str]:
        cmd = "curl -s http://localhost:40403/metrics"
        output = self.exec_run(cmd=cmd)
        return output

    def get_metrics_strict(self):
        output = self.shell_out("curl", "-s", "http://localhost:40403/metrics")
        return output

    def deploy_and_propose(self, **deploy_kwargs) -> str:
        if "from_address" not in deploy_kwargs:
            deploy_kwargs["from_address"] = self.from_address
        deploy_output = self.client.deploy(**deploy_kwargs)
        assert "Success!" in deploy_output
        block_hash = extract_block_hash_from_propose_output(self.client.propose())
        assert block_hash is not None
        logging.info(
            f"The block hash: {block_hash} generated for {self.container.name}"
        )
        return block_hash

    def deploy_and_propose_with_retry(
        self, max_attempts: int, retry_seconds: int, **deploy_kwargs
    ) -> str:
        deploy_output = self.client.deploy(**deploy_kwargs)
        assert "Success!" in deploy_output
        block_hash = self.client.propose_with_retry(max_attempts, retry_seconds)
        assert block_hash is not None
        logging.info(
            f"The block hash: {block_hash} generated for {self.container.name}"
        )
        return block_hash

    def transfer_to_account(
        self,
        to_account_id: int,
        amount: int,
        from_account_id: Union[str, int] = "genesis",
    ) -> str:
        """
        Performs a transfer using the from account if given (or genesis if not)

        :param to_account_id: 1-20 index of test account for transfer into
        :param amount: amount of motes to transfer (mote = smallest unit of token)
        :param from_account_id: default 'genesis' account, but previously funded account_id is also valid.
        :returns block_hash in hex str
        """
        logging.info(f"=== Transfering {amount} to {to_account_id}")

        assert (
            is_valid_account(to_account_id) and to_account_id != "genesis"
        ), "Can transfer only to non-genesis accounts in test framework (1-20)."
        assert is_valid_account(
            from_account_id
        ), "Must transfer from a valid account_id: 1-20 or 'genesis'"

        from_account = Account(from_account_id)
        to_account = Account(to_account_id)
        args_json = json.dumps(
            [{"account": to_account.public_key_hex}, {"u32": amount}]
        )
        with from_account.public_key_path as public_key_path, from_account.private_key_path as private_key_path:
            response, deploy_hash_bytes = self.p_client.deploy(
                from_address=from_account.public_key_hex,
                session_contract="transfer_to_account.wasm",
                payment_contract="transfer_to_account.wasm",
                public_key=public_key_path,
                private_key=private_key_path,
                args=self.p_client.abi.args_from_json(args_json),
            )

        deploy_hash_hex = deploy_hash_bytes.hex()
        assert len(deploy_hash_hex) == 64

        response = self.p_client.propose()

        block_hash = response.block_hash.hex()
        assert len(deploy_hash_hex) == 64

        for deploy_info in self.p_client.show_deploys(block_hash):
            assert deploy_info.is_error is False

        return block_hash

    def bond(
        self,
        session_contract: str,
        payment_contract: str,
        amount: int,
        from_account_id: Union[str, int] = "genesis",
    ) -> str:
        abi_json_args = json.dumps([{"u32": amount}])
        return self._deploy_and_propose_with_abi_args(
            session_contract, payment_contract, Account(from_account_id), abi_json_args
        )

    def unbond(
        self,
        session_contract: str,
        payment_contract: str,
        maybe_amount: Optional[int] = None,
        from_account_id: Union[str, int] = "genesis",
    ) -> str:
        amount = 0 if maybe_amount is None else maybe_amount
        abi_json_args = json.dumps([{"u32": amount}])
        return self._deploy_and_propose_with_abi_args(
            session_contract, payment_contract, Account(from_account_id), abi_json_args
        )

    def _deploy_and_propose_with_abi_args(
        self,
        session_contract: str,
        payment_contract: str,
        from_account: Account,
        json_args: str,
    ) -> str:

        response, deploy_hash_bytes = self.p_client.deploy(
            from_address=from_account.public_key_hex,
            session_contract=session_contract,
            payment_contract=payment_contract,
            public_key=from_account.public_key_path,
            private_key=from_account.private_key_path,
            args=self.p_client.abi.args_from_json(json_args),
        )

        deploy_hash_hex = deploy_hash_bytes.hex()
        assert len(deploy_hash_hex) == 64

        response = self.p_client.propose()

        block_hash = response.block_hash.hex()
        assert len(deploy_hash_hex) == 64

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

    def blocks_as_list_with_depth(self, depth: int) -> List:
        # TODO: Replace with generator using Python client
        result = self.client.show_blocks(depth)
        block_list = []
        for i, section in enumerate(result.split(" ---------------\n")):
            if i == 0:
                continue
            cur_block = {}
            for line in section.split("\n"):
                try:
                    name, value = line.split(": ", 1)
                    cur_block[name] = value.replace('"', "")
                except ValueError:
                    pass
            block_list.append(cur_block)
        block_list.reverse()
        return block_list

    @property
    def address(self) -> str:
        m = re.search(
            f"Listening for traffic on (casperlabs://.+@{self.container.name}\\?protocol=\\d+&discovery=\\d+)\\.$",
            self.logs(),
            re.MULTILINE | re.DOTALL,
        )
        if m is None:
            raise CasperLabsNodeAddressNotFoundError()
        address = m.group(1)
        return address
