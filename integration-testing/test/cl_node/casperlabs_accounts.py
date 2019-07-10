from dataclasses import dataclass
from typing import Union, Optional, List
from pathlib import Path
import base64
import os


TRANSFER_AMOUNTS = [0, 1000000, 750000] + [1000000] * 18


@dataclass
class Account:
    file_id: Union[int, str]

    @staticmethod
    def int_key_to_hex(key: List[int]) -> str:
        return ''.join([f"{int(ch):#04x}"[2:] for ch in key])

    @property
    def account_path(self) -> Path:
        cur_path = Path(os.path.realpath(__file__)).parent
        while cur_path.name != 'integration-testing':
            cur_path = cur_path.parent
        return cur_path / 'resources' / 'accounts'

    @property
    def public_key_path(self) -> Path:
        return self.account_path / self.public_key_filename

    @property
    def private_key_path(self) -> Path:
        return self.account_path / self.private_key_filename

    @property
    def private_key_filename(self) -> str:
        return f'account-private-{self.file_id}.pem'

    @property
    def public_key_filename(self) -> str:
        return f'account-public-{self.file_id}.pem'

    @property
    def public_key(self) -> str:
        with open(self.account_path / f'account-id-{self.file_id}') as f:
            return f.read().strip()

    @property
    def public_key_hex(self) -> str:
        return base64.b64decode(self.public_key).hex()

    @property
    def public_key_int_list(self) -> List[int]:
        pkh = self.public_key_hex
        return [int(pkh[i:i+2], 16) for i in range(0, len(pkh), 2)]

    @property
    def transfer_amount(self) -> Optional[int]:
        index = 0 if self.file_id == 'genesis' else self.file_id
        return TRANSFER_AMOUNTS[index]

    @property
    def transfer_contract(self) -> Optional[str]:
        if self.file_id in [1, 2]:
            return f'test_transfer_to_account_{self.file_id}_call.wasm'
        return None


GENESIS_ACCOUNT = Account('genesis')


if __name__ == '__main__':

    print(f'Rust Code for it_common lib.rs:')
    for key in ['genesis'] + list(range(1, 21)):
        acct = Account(key)
        print(f'const ACCOUNT_{key}_ADDR: [u8;32] = {acct.public_key_int_list};')
        print(f'const ACCOUNT_{key}_TRANSFER_AMOUNT: u32 = {acct.transfer_amount};')
        print('')
