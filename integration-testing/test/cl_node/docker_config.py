import os
from dataclasses import dataclass
from typing import Any, Optional
from docker import DockerClient


from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT, Account
from test.cl_node.common import random_string, BOOTSTRAP_PATH


DEFAULT_NODE_ENV = {
    "RUST_BACKTRACE": "full",
    "CL_LOG_LEVEL": os.environ.get("CL_LOG_LEVEL", "INFO"),
    "CL_CASPER_IGNORE_DEPLOY_SIGNATURE": "false",
    "CL_SERVER_NO_UPNP": "true",
    "CL_VERSION": "test",
}


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
    use_new_gossiping: bool = True
    is_payment_code_enabled: bool = False
    initial_motes: int = 100 * (10 ** 9)  # 100 billion
    socket_volume: Optional[str] = None
    node_account: Account = None
    grpc_encryption: bool = False

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

    def tls_certificate_local_path(self):
        return f"resources/bootstrap_certificate/node-{self.number}.certificate.pem"

    def node_command_options(self, server_host: str) -> dict:
        options = {
            "--server-default-timeout": "10second",
            "--server-host": server_host,
            "--casper-validator-private-key": self.node_private_key,
            "--grpc-socket": "/root/.casperlabs/sockets/.casper-node.sock",
            "--metrics-prometheus": "",
            "--tls-certificate": self.tls_certificate_path(),
            "--tls-key": self.tls_key_path(),
        }
        if self.grpc_encryption:
            options["--grpc-use-tls"] = ""
        if self.bootstrap_address:
            options["--server-bootstrap"] = self.bootstrap_address
        if self.is_bootstrap:
            gen_acct_key_file = GENESIS_ACCOUNT.public_key_filename
            options[
                "--casper-genesis-account-public-key-path"
            ] = f"/root/.casperlabs/accounts/{gen_acct_key_file}"
            options["--casper-initial-motes"] = self.initial_motes
        if self.node_public_key:
            options["--casper-validator-public-key"] = self.node_public_key
        if self.use_new_gossiping:
            options["--server-use-gossiping"] = ""
        return options
