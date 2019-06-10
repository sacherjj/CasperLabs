from abc import ABC, abstractmethod
from typing import Optional, Any, Generator


class CasperLabsClient(ABC):

    @property
    @abstractmethod
    def client_type(self) -> str:
        return 'abstract'

    @abstractmethod
    def deploy(self,
               from_address: str = "303030303030303030303030303030303030303030303030303030303030303030",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = None, # nonce == None means framework should provide correct nonce
               session_contract: Optional[str] = 'test_helloname.wasm',
               payment_contract: Optional[str] = 'test_helloname.wasm') -> str:
        pass

    @abstractmethod
    def propose(self) -> str:
        pass

    @abstractmethod
    def show_block(self, block_hash: str) -> str:
        pass

    @abstractmethod
    def show_blocks(self, depth: int):
        pass
