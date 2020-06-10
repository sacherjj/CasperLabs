from abc import ABC, abstractmethod
from typing import Union
from pathlib import Path

from casperlabs_client import crypto
from casperlabs_client.io import write_binary_file


class KeyPair(ABC):
    """ Abstract class for loading, generating and handling public/private key pairs """

    def __init__(
        self,
        private_key_pem: bytes = None,
        private_key=None,
        public_key_pem: bytes = None,
        public_key=None,
        algorithm: str = None,
    ):
        self._private_key_pem = private_key_pem
        self._private_key = private_key
        self._public_key_pem = public_key_pem
        self._public_key = public_key
        self._algorithm = algorithm

    @property
    def algorithm(self):
        """ String representation of the key algorithm """
        return self._algorithm

    @property
    @abstractmethod
    def private_key_pem(self):
        pass

    @property
    @abstractmethod
    def private_key(self):
        pass

    @property
    @abstractmethod
    def public_key_pem(self):
        pass

    @property
    @abstractmethod
    def public_key(self):
        pass

    def save_pem_files(
        self, save_directory: Union[str, Path], filename_prefix: str = "account-"
    ) -> None:
        """
        Save key pairs out as public and private pem files.

        :param save_directory:   str or Path to directory for saving pem files.
        :param filename_prefix:  prefix of filename to be used for save.
        """
        private_path = Path(save_directory) / f"{filename_prefix}private.pem"
        write_binary_file(private_path, self.private_key_pem)

        public_path = Path(save_directory) / f"{filename_prefix}public.pem"
        write_binary_file(public_path, self.public_key_pem)

    @staticmethod
    @abstractmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyPair":
        pass

    @abstractmethod
    def sign(self, data: bytes) -> bytes:
        pass

    @staticmethod
    def _parse_pem_data_line(pem_file_data: bytes):
        """
        :param pem_file_data: data from pem file as bytes
        :return: raw bytes from the data line
        """
        for line in pem_file_data.split(b"\n"):
            if not line.startswith(b"-----"):
                return line

    def account_hash(self) -> bytes:
        """ Generate hash of public key and key algorithm for use as primary identifier in the system """
        return crypto.blake2b_hash(
            self.algorithm.encode("UTF-8") + b"0x00" + self.public_key
        )
