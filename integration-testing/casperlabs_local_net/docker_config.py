import os
from dataclasses import dataclass
from typing import Any, Optional, Callable
from docker import DockerClient


from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.common import random_string, BOOTSTRAP_PATH, testing_root_path
from casperlabs_local_net.cli import CLI


DEFAULT_NODE_ENV = {
    "RUST_BACKTRACE": "full",
    "CL_LOG_LEVEL": os.environ.get("CL_LOG_LEVEL", "INFO"),
    "CL_SERVER_NO_UPNP": "true",
    "CL_VERSION": "test",
}


def default_bond_amount(i, n):
    """
    Default amount for the i-th out of n bonding accounts in accounts.csv.
    """
    return n + 2 * i


@dataclass
class DockerConfig:
    """
    This holds all information that will be needed for creating both docker containers for a CL_Node
    """

    docker_client: DockerClient
    node_private_key: str
    node_public_key: str = None
    node_env: dict = None
    network: Optional[Any] = None
    number: int = 0
    rand_str: Optional[str] = None
    command_timeout: int = 180
    mem_limit: str = "4G"
    is_bootstrap: bool = False
    is_validator: bool = True
    is_signed_deploy: bool = True
    bootstrap_address: Optional[str] = None
    initial_motes: int = 100 * (10 ** 9)  # 100 billion
    socket_volume: Optional[str] = None
    node_account: Account = None
    grpc_encryption: bool = False
    auto_propose: bool = False
    is_read_only: bool = False
    behind_proxy: bool = False
    # Function that returns bonds amount for each account to be placed in accounts.csv.
    bond_amount: Callable = default_bond_amount
    custom_docker_tag: Optional[str] = None
    chainspec_directory: Optional[str] = None
    keys_directory: Optional[str] = None
    # CLI or DockerCLI, for running keygen
    cli_class: Optional[CLI] = None
    etc_casperlabs_directory: str = "etc_casperlabs_empty"

    def __post_init__(self):
        if self.rand_str is None:
            self.rand_str = random_string(5)
        if self.node_env is None:
            self.node_env = DEFAULT_NODE_ENV
        java_options = os.environ.get("_JAVA_OPTIONS")
        if java_options is not None:
            self.node_env["_JAVA_OPTIONS"] = java_options

    def tls_certificate_path(self):
        return f"{BOOTSTRAP_PATH}/node-{self.number}.certificate.pem"

    def tls_key_path(self):
        return f"{BOOTSTRAP_PATH}/node-{self.number}.key.pem"

    def tls_key_local_path(self):
        return f"{str(testing_root_path())}/resources/bootstrap_certificate/node-{self.number}.key.pem"

    def tls_certificate_local_path(self):
        return f"{str(testing_root_path())}/resources/bootstrap_certificate/node-{self.number}.certificate.pem"

    def node_command_options(self, node, server_host: str) -> dict:
        options = {
            "--server-default-timeout": "10second",
            "--server-approval-poll-interval": "10second",
            "--server-init-sync-round-period": "10second",
            "--server-alive-peers-cache-expiration-period": "10second",
            "--server-host": server_host,
            "--grpc-socket": "/root/.casperlabs/sockets/.casper-node.sock",
            "--metrics-prometheus": "",
            "--tls-certificate": self.tls_certificate_path(),
            "--tls-key": self.tls_key_path(),
            "--tls-api-certificate": self.tls_certificate_path(),
            "--tls-api-key": self.tls_key_path(),
        }
        if self.behind_proxy:
            options["--server-port"] = "50400"
            options["--server-kademlia-port"] = "50404"
        if not self.is_read_only:
            options["--casper-validator-private-key"] = self.node_private_key
        if self.grpc_encryption:
            options["--grpc-use-tls"] = ""
        if self.bootstrap_address:
            options["--server-bootstrap"] = self.bootstrap_address
        if self.node_public_key:
            options["--casper-validator-public-key"] = self.node_public_key
        if self.chainspec_directory and "empty" in self.etc_casperlabs_directory:
            # If the chainspec_directory doesn't match the name of directory with chainspec
            # in resources bundled with node, then we have to provide the
            # option --casper-chain-spec-path pointing to it.
            # In this case we have to provide full chainspec,
            # including manifest.toml and the system contracts.
            options["--casper-chain-spec-path"] = node.CL_CHAINSPEC_DIR
        if self.auto_propose:
            options["--casper-auto-propose-enabled"] = ""
        return options


class KeygenDockerConfig(DockerConfig):
    def content(self, file_name):
        with open(os.path.join(self.keys_directory, file_name)) as f:
            s = f.read()
            if not file_name.endswith(".pem"):
                return s
            else:
                data_lines = [l for l in s.splitlines() if not l.startswith("-----")]
                return data_lines[0]

    def path(self, file_name):
        return os.path.join("/root/.casperlabs", self.keys_directory, file_name)

    def node_command_options(self, node, server_host: str) -> dict:
        options = super().node_command_options(node, server_host)
        options["--casper-validator-private-key"] = self.content(
            "validator-private.pem"
        )
        options["--casper-validator-public-key"] = self.content("validator-public.pem")
        options["--tls-certificate"] = self.path("node.certificate.pem")
        options["--tls-key"] = self.path("node.key.pem")
        options["--tls-api-certificate"] = self.path("node.certificate.pem")
        options["--tls-api-key"] = self.path("node.key.pem")
        return options
