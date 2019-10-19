import time
from typing import Optional, Union, Any, Iterable
from dataclasses import dataclass
import docker.errors
import json
from pathlib import Path

from casperlabs_local_net import LoggingMixin
from casperlabs_local_net.common import (
    extract_block_count_from_show_blocks,
    extract_block_hash_from_propose_output,
    random_string,
    Contract,
    DEFAULT_PAYMENT_COST,
    resources_path,
)
from casperlabs_local_net.client_base import CasperLabsClientBase
from casperlabs_local_net.errors import NonZeroExitCodeError
from casperlabs_local_net.client_parser import parse, parse_show_deploys
from casperlabs_client import extract_common_name


def resource(file_name):
    return resources_path() / file_name


_STANDARD_PAYMENT_JSON = json.dumps(
    [
        {
            "name": "amount",
            "value": {
                "big_int": {"value": f"{DEFAULT_PAYMENT_COST}", "bit_width": 512}
            },
        }
    ]
)


@dataclass
class Arg:
    argument: str
    value: Optional[Any]


class DockerClient(CasperLabsClientBase, LoggingMixin):
    def __init__(self, node: "DockerNode"):  # NOQA
        self.node = node
        self.abi = (
            None
        )  # TODO: Translate Client ABI to similar to Python if implemented
        self.docker_client = node.config.docker_client
        super(DockerClient, self).__init__()

    @property
    def client_type(self) -> str:
        return "docker"

    def invoke_client(
        self, command: str, decode_stdout: bool = True, add_host: bool = True
    ) -> str:
        volumes = {
            self.node.host_mount_dir: {"bind": "/data", "mode": "ro"},
            "/tmp": {"bind": "/tmp", "mode": "rw"},
        }
        if add_host:
            command = f"--host {self.node.container_name} {command}"
            if self.node.cl_network.grpc_encryption:
                node_id = extract_common_name(
                    self.node.config.tls_certificate_local_path()
                )
                command = f"--node-id {node_id} {command}"
        self.logger.info(f"COMMAND {command}")
        container = self.docker_client.containers.run(
            image=f"casperlabs/client:{self.node.docker_tag}",
            name=f"client-{self.node.config.number}-{random_string(5)}",
            command=command,
            network=self.node.config.network,
            volumes=volumes,
            detach=True,
            stderr=True,
            stdout=True,
        )
        r = container.wait()
        status_code = r["StatusCode"]
        stdout_raw = container.logs(stdout=True, stderr=False)
        stdout = decode_stdout and stdout_raw.decode("utf-8") or stdout_raw
        stderr = container.logs(stdout=False, stderr=True).decode("utf-8")

        # TODO: I don't understand why bug if I just call `self.logger.debug` then
        # it doesn't print anything, even though the level is clearly set.
        if self.log_level == "DEBUG" or status_code != 0:
            self.logger.info(
                f"EXITED exit_code: {status_code} STDERR: {stderr} STDOUT: {stdout}"
            )

        try:
            container.remove()
        except docker.errors.APIError as e:
            self.logger.warning(
                f"Exception while removing docker client container: {str(e)}"
            )

        if status_code:
            raise NonZeroExitCodeError(
                command=(command, status_code), exit_code=status_code, output=stderr
            )

        return stdout

    def propose(self) -> str:
        return self.invoke_client("propose")

    def propose_with_retry(self, max_attempts: int, retry_seconds: int) -> str:
        # TODO: Is this still true with Nonces gone.
        # With many threads using the same account the nonces will be interleaved.
        # Only one node can propose at a time, the others have to wait until they
        # receive the block and then try proposing again.
        attempt = 0
        while True:
            try:
                return extract_block_hash_from_propose_output(self.propose())
            except NonZeroExitCodeError as ex:
                if attempt < max_attempts:
                    self.logger.debug("Could not propose; retrying later.")
                    attempt += 1
                    time.sleep(retry_seconds)
                else:
                    self.logger.debug("Could not propose; no more retries!")
                    raise ex

    def get_balance(self, account_address: str, block_hash: str) -> int:
        """
        Deprecated to make interface common to CLI naming.
        """
        return self.balance(account_address, block_hash)

    def balance(self, account_address: str, block_hash: str) -> int:
        """
        Returns get_balance of account according to block given.

        :param account_address: account public key in hex
        :param block_hash: block_hash in hex
        :return: balance as int
        """
        command = f"balance --address {account_address} --block-hash {block_hash}"
        r = self.invoke_client(command)
        try:
            balance = r.split(" : ")[1]
            return int(balance)
        except Exception as e:
            raise Exception(f"Error parsing: {r}.\n{e}")

    def deploy(
        self,
        from_address: str = None,
        gas_price: int = 10,
        nonce: int = None,  # nonce == None means framework should provide correct nonce
        session_contract: Optional[Union[str, Path]] = None,
        session_args: Optional[str] = None,
        payment_contract: Optional[Union[str, Path]] = Contract.STANDARD_PAYMENT,
        payment_args: Optional[str] = _STANDARD_PAYMENT_JSON,
        public_key: Optional[Union[str, Path]] = None,
        private_key: Optional[Union[str, Path]] = None,
    ) -> str:

        if session_contract is None:
            raise ValueError(f"session_contract is required.")

        address = from_address or self.node.test_account.public_key_hex

        def docker_account_path(p):
            """Convert path of account key file to docker client's path in /data"""
            return Path(*(["/data"] + str(p).split("/")[-2:]))

        public_key = docker_account_path(
            public_key or self.node.test_account.public_key_path
        )
        private_key = docker_account_path(
            private_key or self.node.test_account.private_key_path
        )

        command = (
            f"deploy --from {address}"
            f" --gas-price {gas_price}"
            f" --session=/data/{session_contract}"
            f" --payment=/data/{payment_contract}"
            f" --private-key={private_key}"
            f" --public-key={public_key}"
            f" --payment-args='{payment_args}'"
        )
        if session_args:
            command += f" --session-args='{session_args}'"

        return self.invoke_client(command)

    @staticmethod
    def _filtered_args(args: Iterable[Arg]) -> str:
        filtered = [
            f"{arg.argument} {arg.value}" for arg in args if arg.value is not None
        ]
        return " ".join(filtered)

    def bond(
        self,
        amount: int,
        private_key: Union[Path, str],
        payment_amount: int = DEFAULT_PAYMENT_COST,
        gas_price: int = 10,
        payment_args: Optional[str] = None,
        payment_hash: Optional[str] = None,
        payment_name: Optional[str] = None,
        payment_path: Optional[str] = None,
        payment_uref: Optional[str] = None,
        session: Optional[str] = None,
        session_args: Optional[str] = None,
        session_hash: Optional[str] = None,
        session_name: Optional[str] = None,
        session_uref: Optional[str] = None,
    ) -> str:
        """
        Bond a node to the network

        :param amount: amount of motes to bond
        :param private_key: path to the file with account private key (Ed25519)
        :param payment_amount: Standard payment amount. Use this with the default payment.
        :param gas_price: The price of gas for this transaction in motes/gas. Must be positive integer.
        :param payment_args: JSON encoded list of Deploy.Arg protobuf messages
        :param payment_hash: Hash (base16) of the stored contract to be called in the payment
        :param payment_name: Name of the stored contract (associated with the executing account) to call in payment.
        :param payment_path: Path to the file with payment code.
        :param payment_uref: URef (base16) of the stored contract to be called in the payment.
        :param session: Path to the file with session code.
        :param session_args: JSON encoded list of Deploy.Arg protobuf messages
        :param session_hash: Hash (base16) of the stored contract to be called in the session.
        :param session_name: Name of the stored contract (associated with the executing account) to call in the session.
        :param session_uref: URef (base16) of the stored contract to be called in the session.
        """
        args = (
            Arg("--amount", amount),
            Arg("--gas-price", gas_price),
            Arg("--payment-amount", payment_amount),
            Arg("--payment-args", payment_args),
            Arg("--payment-hash", payment_hash),
            Arg("--payment-name", payment_name),
            Arg("--payment=path", payment_path),
            Arg("--payment-uref", payment_uref),
            Arg("--private-key", private_key),
            Arg("--session", session),
            Arg("--session-args", session_args),
            Arg("--session-hash", session_hash),
            Arg("--session-name", session_name),
            Arg("--session-uref", session_uref),
        )
        return self.invoke_client(f"bond {self._filtered_args(args)}")

    def unbond(
        self,
        amount: int,
        private_key: Union[Path, str],
        payment_amount: int = DEFAULT_PAYMENT_COST,
        gas_price: int = 10,
        payment_args: Optional[str] = None,
        payment_hash: Optional[str] = None,
        payment_name: Optional[str] = None,
        payment_path: Optional[str] = None,
        payment_uref: Optional[str] = None,
        session: Optional[str] = None,
        session_args: Optional[str] = None,
        session_hash: Optional[str] = None,
        session_name: Optional[str] = None,
        session_uref: Optional[str] = None,
    ) -> str:
        """
        Unbond a node from the network

        :param amount: amount of motes to unbond
        :param private_key: path to the file with account private key (Ed25519)
        :param payment_amount: Standard payment amount. Use this with the default payment.
        :param gas_price: The price of gas for this transaction in motes/gas. Must be positive integer.
        :param payment_args: JSON encoded list of Deploy.Arg protobuf messages
        :param payment_hash: Hash (base16) of the stored contract to be called in the payment
        :param payment_name: Name of the stored contract (associated with the executing account) to call in payment.
        :param payment_path: Path to the file with payment code.
        :param payment_uref: URef (base16) of the stored contract to be called in the payment.
        :param session: Path to the file with session code.
        :param session_args: JSON encoded list of Deploy.Arg protobuf messages
        :param session_hash: Hash (base16) of the stored contract to be called in the session.
        :param session_name: Name of the stored contract (associated with the executing account) to call in the session.
        :param session_uref: URef (base16) of the stored contract to be called in the session.
        """
        args = (
            Arg("--amount", amount),
            Arg("--private-key", private_key),
            Arg("--payment-amount", payment_amount),
            Arg("--gas-price", gas_price),
            Arg("--payment-args", payment_args),
            Arg("--payment-hash", payment_hash),
            Arg("--payment-name", payment_name),
            Arg("--payment-path", payment_path),
            Arg("--payment-uref", payment_uref),
            Arg("--session", session),
            Arg("--session-args", session_args),
            Arg("--session-hash", session_hash),
            Arg("--session-name", session_name),
            Arg("--session-uref", session_uref),
        )
        return self.invoke_client(f"unbond {self._filtered_args(args)}")

    def transfer(
        self,
        amount: int,
        private_key: Union[Path, str],
        target_account: str,
        payment_amount: int = DEFAULT_PAYMENT_COST,
        gas_price: int = 10,
        payment_args: Optional[str] = None,
        payment_hash: Optional[str] = None,
        payment_name: Optional[str] = None,
        payment_path: Optional[str] = None,
        payment_uref: Optional[str] = None,
        session: Optional[str] = None,
        session_args: Optional[str] = None,
        session_hash: Optional[str] = None,
        session_name: Optional[str] = None,
        session_uref: Optional[str] = None,
    ) -> str:
        """
        Transfer funds between accounts

        :param amount: amount of motes to transfer
        :param private_key: path to the file with from account private key (Ed25519)
        :param target_account: base64 representation of target account's public key
        :param payment_amount: Standard payment amount. Use this with the default payment.
        :param gas_price: The price of gas for this transaction in motes/gas. Must be positive integer.
        :param payment_args: JSON encoded list of Deploy.Arg protobuf messages
        :param payment_hash: Hash (base16) of the stored contract to be called in the payment
        :param payment_name: Name of the stored contract (associated with the executing account) to call in payment.
        :param payment_path: Path to the file with payment code.
        :param payment_uref: URef (base16) of the stored contract to be called in the payment.
        :param session: Path to the file with session code.
        :param session_args: JSON encoded list of Deploy.Arg protobuf messages
        :param session_hash: Hash (base16) of the stored contract to be called in the session.
        :param session_name: Name of the stored contract (associated with the executing account) to call in the session.
        :param session_uref: URef (base16) of the stored contract to be called in the session.
        """
        args = (
            Arg("--amount", amount),
            Arg("--private-key", private_key),
            Arg("--target-account", target_account),
            Arg("--payment-amount", payment_amount),
            Arg("--gas-price", gas_price),
            Arg("--payment-args", payment_args),
            Arg("--payment-hash", payment_hash),
            Arg("--payment-name", payment_name),
            Arg("--payment-path", payment_path),
            Arg("--payment-uref", payment_uref),
            Arg("--session", session),
            Arg("--session-args", session_args),
            Arg("--session-hash", session_hash),
            Arg("--session-name", session_name),
            Arg("--session-uref", session_uref),
        )
        return self.invoke_client(f"transfer {self._filtered_args(args)}")

    def make_deploy(
        self,
        deploy_path: Union[Path, str],
        private_key: Union[Path, str],
        public_key: Union[Path, str],
        from_addr: str,
        payment_amount: int = DEFAULT_PAYMENT_COST,
        gas_price: int = 10,
        payment_args: Optional[str] = None,
        payment_hash: Optional[str] = None,
        payment_name: Optional[str] = None,
        payment_path: Optional[str] = None,
        payment_uref: Optional[str] = None,
        session: Optional[str] = None,
        session_args: Optional[str] = None,
        session_hash: Optional[str] = None,
        session_name: Optional[str] = None,
        session_uref: Optional[str] = None,
    ) -> str:
        """
        Transfer funds between accounts

        :param deploy_path: Path to the file where deploy will be saved.
        :param private_key: path to the file with from account private key (Ed25519)
        :param public_key: path to the file with from account public key (Ed25519)
        :param from_addr: Public key (base16) of the account which is the context of this deployment
        :param payment_amount: Standard payment amount. Use this with the default payment.
        :param gas_price: The price of gas for this transaction in motes/gas. Must be positive integer.
        :param payment_args: JSON encoded list of Deploy.Arg protobuf messages
        :param payment_hash: Hash (base16) of the stored contract to be called in the payment
        :param payment_name: Name of the stored contract (associated with the executing account) to call in payment.
        :param payment_path: Path to the file with payment code.
        :param payment_uref: URef (base16) of the stored contract to be called in the payment.
        :param session: Path to the file with session code.
        :param session_args: JSON encoded list of Deploy.Arg protobuf messages
        :param session_hash: Hash (base16) of the stored contract to be called in the session.
        :param session_name: Name of the stored contract (associated with the executing account) to call in the session.
        :param session_uref: URef (base16) of the stored contract to be called in the session.
        """
        args = (
            Arg("--deploy-path", deploy_path),
            Arg("--private-key", private_key),
            Arg("--public-key", public_key),
            Arg("--from", from_addr),
            Arg("--payment-amount", payment_amount),
            Arg("--gas-price", gas_price),
            Arg("--payment-args", payment_args),
            Arg("--payment-hash", payment_hash),
            Arg("--payment-name", payment_name),
            Arg("--payment", payment_path),
            Arg("--payment-uref", payment_uref),
            Arg("--session", session),
            Arg("--session-args", session_args),
            Arg("--session-hash", session_hash),
            Arg("--session-name", session_name),
            Arg("--session-uref", session_uref),
        )
        return self.invoke_client(f"make-deploy {self._filtered_args(args)}")

    def send_deploy(self, deploy_path: Union[Path, str]) -> str:
        args = f"send-deploy --deploy-path {deploy_path}"
        return self.invoke_client(args)

    def show_block(self, block_hash: str) -> str:
        return self.invoke_client(f"show-block {block_hash}")

    def show_blocks(self, depth: int) -> str:
        return self.invoke_client(f"show-blocks --depth={depth}")

    def get_blocks_count(self, depth: int) -> int:
        show_blocks_output = self.show_blocks(depth)
        return extract_block_count_from_show_blocks(show_blocks_output)

    def vdag(self, depth: int, show_justification_lines: bool = False) -> str:
        just_text = "--show-justification-lines" if show_justification_lines else ""
        return self.invoke_client(f"vdag --depth {depth} {just_text}")

    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        """
        Subcommand: query-state - Query a value in the global state.
          -b, --block-hash  <arg>   Hash of the block to query the state of
          -k, --key  <arg>          Base16 encoding of the base key.
          -p, --path  <arg>         Path to the value to query. Must be of the form
                                    'key1/key2/.../keyn'
          -t, --type  <arg>         Type of base key. Must be one of 'hash', 'uref',
                                    'address'
          -h, --help                Show help message

        """
        return parse(
            self.invoke_client(
                f"query-state "
                f' --block-hash "{block_hash}"'
                f' --key "{key}"'
                f' --path "{path}"'
                f' --type "{key_type}"'
            )
        )

    def show_deploys(self, hash: str):
        return parse_show_deploys(self.invoke_client(f"show-deploys {hash}"))

    def show_deploy(self, hash: str):
        return parse(self.invoke_client(f"show-deploy {hash}"))

    def query_purse_balance(self, block_hash: str, purse_id: str) -> Optional[float]:
        raise NotImplementedError()

    def deploy_and_propose(self, **deploy_kwargs) -> str:
        if "from_address" not in deploy_kwargs:
            deploy_kwargs["from_address"] = self.node.from_address

        deploy_output = self.deploy(**deploy_kwargs)

        if "Success!" not in deploy_output:
            raise Exception(f"Deploy failed: {deploy_output}")

        propose_output = self.propose()

        block_hash = extract_block_hash_from_propose_output(propose_output)

        if block_hash is None:
            raise Exception(
                f"Block Hash not extracted from propose output: {propose_output}"
            )

        self.logger.info(
            f"The block hash: {block_hash} generated for {self.node.container_name}"
        )

        return block_hash

    def deploy_and_propose_with_retry(
        self, max_attempts: int, retry_seconds: int, **deploy_kwargs
    ) -> str:
        deploy_output = self.deploy(**deploy_kwargs)
        if "Success!" not in deploy_output:
            raise Exception(f"Deploy failed: {deploy_output}")

        block_hash = self.propose_with_retry(max_attempts, retry_seconds)
        if block_hash is None:
            raise Exception(f"Block Hash not received from propose_with_retry.")

        self.logger.info(
            f"The block hash: {block_hash} generated for {self.node.container.name}"
        )

        return block_hash
