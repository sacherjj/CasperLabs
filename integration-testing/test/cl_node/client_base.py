from abc import ABC, abstractmethod
from typing import Optional, Any, Generator


class CasperLabsClient(ABC):

    @property
    @abstractmethod
    def client_type(self) -> str:
        return 'abstract'

    @abstractmethod
    def deploy(self,
               from_address: str = "00000000000000000000000000000000",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = 0,
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
    def show_blocks_with_depth(self, depth: int):
        pass

    @abstractmethod
    def get_blocks_count(self, depth: int) -> int:
        pass
