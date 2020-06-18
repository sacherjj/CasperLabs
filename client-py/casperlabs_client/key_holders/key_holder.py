from abc import ABC, abstractmethod
from typing import Union
from pathlib import Path

from casperlabs_client import crypto, consts
from casperlabs_client.io import write_binary_file


class KeyHolder(ABC):
    """ Abstract class for loading, generating and handling public/private key holders """

    def __init__(
        self,
        private_key_pem: bytes = None,
        private_key=None,
        public_key_pem: bytes = None,
        public_key=None,
        algorithm: str = None,
    ):
        if not any((private_key, private_key_pem, public_key, public_key_pem)):
            raise ValueError("No public or private key information provided.")
        self._private_key_pem = private_key_pem
        self._private_key = private_key
        self._public_key_pem = public_key_pem
        self._public_key = public_key
        self._algorithm = algorithm

    @property
    def algorithm(self):
        """ String representation of the key algorithm """
        return self._algorithm

    # TODO: Move common key hydration logic into these properties and make methods for converting
    # TODO: between key data formats that are implemented in the key algorithm classes.

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
        self,
        save_directory: Union[str, Path],
        filename_prefix: str = consts.DEFAULT_KEY_FILENAME_PREFIX,
    ) -> None:
        """
        Save key pairs out as public and private pem files.

        :param save_directory:   str or Path to directory for saving pem files.
        :param filename_prefix:  prefix of filename to be used for save.
        """
        private_path = (
            Path(save_directory)
            / f"{filename_prefix}{consts.ACCOUNT_PRIVATE_KEY_FILENAME_SUFFIX}"
        )
        write_binary_file(private_path, self.private_key_pem)

        public_path = (
            Path(save_directory)
            / f"{filename_prefix}{consts.ACCOUNT_PUBLIC_KEY_FILENAME_SUFFIX}"
        )
        write_binary_file(public_path, self.public_key_pem)

    @staticmethod
    @abstractmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyHolder":
        pass

    @staticmethod
    @abstractmethod
    def from_public_key_path(public_key_pem_path: Union[str, Path]) -> "KeyHolder":
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

    @property
    def account_hash(self) -> bytes:
        """ Generate hash of public key and key algorithm for use as primary identifier in the system as bytes """
        # account hash is the one place where algorithm is used in upper case.
        return crypto.blake2b_hash(
            self.algorithm.upper().encode("UTF-8") + b"\x00" + self.public_key
        )

    @property
    def account_hash_hex(self) -> str:
        """ Generate hash of public key and key algorithm for use as primary identifier in the system as hex str """
        return self.account_hash.hex()
