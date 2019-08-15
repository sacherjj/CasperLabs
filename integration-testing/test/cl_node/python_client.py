from typing import Optional
import os
import logging
import time

from test.cl_node import LoggingMixin
from test.cl_node.nonce_registry import NonceRegistry
from casperlabs_client import CasperLabsClient, ABI, InternalError


class PythonClient(CasperLabsClient, LoggingMixin):
    def __init__(self, node: "DockerNode"):  # NOQA
        super(PythonClient, self).__init__()
        self.node = node
        self.abi = ABI
        # If $TAG_NAME is set it means we are running in docker, see docker_run_test.sh
        host = (
            os.environ.get("TAG_NAME", None) and self.node.container_name or "localhost"
        )

        self.client = CasperLabsClient(
            host=host,
            internal_port=self.node.grpc_internal_docker_port,
            port=self.node.grpc_external_docker_port,
        )
        logging.info(
            f"PythonClient(host={self.client.host}, "
            f"port={self.node.grpc_external_docker_port}, "
            f"internal_port={self.node.grpc_internal_docker_port})"
        )

    @property
    def client_type(self) -> str:
        return "python"

    def deploy(
        self,
        from_address: str = None,
        gas_limit: int = 1000000,
        gas_price: int = 1,
        nonce: int = None,
        session_contract: Optional[str] = None,
        payment_contract: Optional[str] = None,
        private_key: Optional[str] = None,
        public_key: Optional[str] = None,
        session_args: list = None,
        payment_args: list = None,
    ) -> str:

        assert session_contract is not None
        assert payment_contract is not None

        public_key = public_key or self.node.test_account.public_key_path
        private_key = private_key or self.node.test_account.private_key_path

        address = from_address or self.node.from_address
        deploy_nonce = nonce if nonce is not None else NonceRegistry.next(address)

        resources_path = self.node.resources_folder
        session_contract_path = str(resources_path / session_contract)
        payment_contract_path = str(resources_path / payment_contract)

        logging.info(
            f"PY_CLIENT.deploy(from_address={address}, gas_limit={gas_limit}, gas_price={gas_price}, "
            f"payment_contract={payment_contract_path}, session_contract={session_contract_path}, "
            f"private_key={private_key}, "
            f"public_key={public_key}, "
            f"nonce={deploy_nonce})"
        )

        try:
            r = self.client.deploy(
                address.encode("UTF-8"),
                gas_limit,
                gas_price,
                payment_contract_path,
                session_contract_path,
                deploy_nonce,
                public_key,
                private_key,
                session_args,
                payment_args,
            )
            return r
        except Exception:
            if nonce is None:
                NonceRegistry.revert(address)
            raise

    def propose(self) -> str:
        logging.info(f"PY_CLIENT.propose() for {self.client.host}")
        return self.client.propose()

    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        return self.client.queryState(block_hash, key, path, key_type)

    def show_block(self, block_hash: str):
        return self.client.showBlock(block_hash)

    def show_blocks(self, depth: int):
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        return len(list(self.show_blocks(depth)))

    def show_deploys(self, block_hash: str):
        return self.client.showDeploys(block_hash)

    def show_deploy(self, deploy_hash: str):
        return self.client.showDeploy(deploy_hash)

    def propose_with_retry(self, max_attempts: int, retry_seconds: int) -> str:
        attempt = 0
        while True:
            try:
                return self.propose()
            except InternalError as ex:
                if attempt < max_attempts:
                    self.logger.debug("Could not propose; retrying later.")
                    attempt += 1
                    time.sleep(retry_seconds)
                else:
                    self.logger.debug("Could not propose; no more retries!")
                    raise ex
