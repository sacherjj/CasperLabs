from dataclasses import dataclass
from tempfile import NamedTemporaryFile
from contextlib import contextmanager
from typing import Union, Optional, List
from pathlib import Path
import base64
import os


def is_valid_account(account_id: Union[int, str]) -> bool:
    try:
        if account_id == 'genesis':
            return True
        return 1 <= account_id <= 300
    except TypeError:
        return False


# TODO Refactor 'genesis' file_id to 0 to be common with other keys 'node_num'

@dataclass
class Account:
    file_id: Union[int, str]

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
    def public_key_binary(self) -> bytes:
        return base64.b64decode(self.public_key + '===')

    @property
    def public_key_binary_filename(self):
        return f'account-bin-{self.file_id}'

    @property
    def public_key_binary_path(self):
        return self.account_path / self.public_key_binary_filename

    @contextmanager
    def public_key_binary_file(self):
        """
        Creates a temp file containing the binary version of public key.

        :return: path to binary file of public key for signing with Python Client
        """
        # TODO: Refactor code to use public_key_binary_path
        yield self.public_key_binary_path


GENESIS_ACCOUNT = Account('genesis')


if __name__ == '__main__':

    print(f'Rust Code for it_common lib.rs:')
    for key in ['genesis'] + list(range(1, 301)):
        acct = Account(key)
        with open(acct.public_key_binary_path, 'wb') as f:
            f.write(acct.public_key_binary)
        print(f'const ACCOUNT_{str(key).upper()}_ADDR: [u8;32] = {acct.public_key_int_list};')
