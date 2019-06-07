from typing import Optional
import logging

from test.cl_node.client_base import CasperLabsClient
#from casper_client import CasperClient
from collections import defaultdict


class PythonClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.client = None #CasperClient(host=self.node.container_name)
        self.nonce = defaultdict(int)

    @property
    def client_type(self) -> str:
        return 'python'

    def deploy(self,
               from_address: str = "00000000000000000000000000000000",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = None,
               session_contract: Optional[str] = 'test_helloname.wasm',
               payment_contract: Optional[str] = 'test_helloname.wasm') -> str:
        # TODO: Will need actual path to local contracts.
        deploy_nonce = nonce if nonce is not None else self.nonce[from_address]

        logging.info(f'PY_CLIENT.deploy(from_address={from_address}, gas_limit={gas_limit}, gas_price={gas_price}, '
                     f'payment_contract={payment_contract}, session_contract={session_contract}, '
                     f'nonce={deploy_nonce})')

        r = self.client.deploy(from_address.encode('UTF-8'), gas_limit, gas_price,
                                  payment_contract, session_contract, nonce)
        # TODO: check the deploy was successful
        if nonce is None:
            self.nonce[from_address] += 1
        return r

    def propose(self) -> str:
        logging.info(f'PY_CLIENT.propose() for {self.node.container_name}')
        return self.client.propose()

    def show_block(self, block_hash: str) -> str:
        pass

    def show_blocks(self, depth: int):
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        block_count = sum([1 for _ in self.show_blocks(depth)])
        return block_count
