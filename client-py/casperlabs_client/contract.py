import os
import abc
from dataclasses import dataclass
from typing import Dict, Union, Optional

from casperlabs_client.io import read_binary_file
from . import consensus_pb2 as consensus
import pkg_resources

from .abi import ABI
from .consts import DEFAULT_PAYMENT_AMOUNT


def bundled_contract_path(file_name):
    """
    Return path to contract file bundled with the package.
    """
    p = pkg_resources.resource_filename(__name__, file_name)
    if not os.path.exists(p):
        raise Exception(f"Missing bundled contract {file_name} ({p})")
    return p


@dataclass
class ContractCode(abc.ABC):
    """ Abstract class representing many ways of sending in contracts to system """

    wasm_file_path: str = None
    contract_hash: bytes = None
    contract_name: str = None
    contract_uref: bytes = None
    contract_args: list = None

    def __post_init__(self):
        if self.contract_args is None:
            self.contract_args = list()

    @staticmethod
    def _maybe_args_from_json(args: Union[str, bytes]) -> Optional[bytes]:
        """ Parses args from JSON format if exists """
        if args:
            # Encode args if still as str
            if isinstance(args, str):
                return ABI.args_from_json(args)
            else:
                return args
        return None

    @staticmethod
    @abc.abstractmethod
    def from_args(args: Dict) -> "ContractCode":
        pass

    @abc.abstractmethod
    def validate(self) -> None:
        """ Raises exception if object is built with invalid data """
        pass

    def is_valid(self) -> bool:
        """ Checks that object is built with valid data """
        try:
            self.validate()
            return True
        except Exception:
            return False

    def to_protobuf(self) -> consensus.Deploy.Code:
        """ Encode contract into consensus.Deploy.Code """
        if self.wasm_file_path:
            return consensus.Deploy.Code(
                wasm=read_binary_file(self.wasm_file_path), args=self.contract_args
            )
        if self.contract_hash:
            return consensus.Deploy.Code(
                hash=self.contract_hash, args=self.contract_args
            )
        if self.contract_name:
            return consensus.Deploy.Code(
                name=self.contract_name, args=self.contract_args
            )
        if self.contract_uref:
            return consensus.Deploy.Code(
                uref=self.contract_uref, args=self.contract_args
            )
        return self._only_args_encode()

    @abc.abstractmethod
    def _only_args_encode(self) -> consensus.Deploy.Code:
        pass


@dataclass
class SessionCode(ContractCode):
    """ Representation of Session Code to send into the system. """

    def validate(self) -> None:
        """ Validates the object and throws exceptions if problems are found"""
        session_options = (
            self.wasm_file_path,
            self.contract_name,
            self.contract_hash,
            self.contract_uref,
        )
        options_count = len(list(filter(None, session_options)))
        if options_count != 1:
            raise ValueError(
                "Must have one and only one session, session_hash, session_name or session_uref provided"
            )

    @staticmethod
    def from_args(args: Dict) -> "SessionCode":
        """ Creates SessionCode from CLI args """
        wasm_file_path = args.get("session")
        contract_hash = args.get("session_hash")
        contract_name = args.get("session_name")
        contract_uref = args.get("session_uref")
        contract_args = SessionCode._maybe_args_from_json(args.get("session_args"))
        session_code = SessionCode(
            wasm_file_path, contract_hash, contract_name, contract_uref, contract_args
        )
        session_code.validate()
        return session_code

    def _only_args_encode(self) -> consensus.Deploy.Code:
        """ Should be caught in arg parsing for Deploy """
        raise ValueError("No runnable wasm or reference found for session.")


@dataclass
class PaymentCode(ContractCode):
    """ Representation of Payment Code to send into the system. """

    payment_amount: int = None

    def validate(self) -> None:
        """ Validates the object and throws exceptions if problems are found"""
        payment_options = [
            self.wasm_file_path,
            self.contract_name,
            self.contract_hash,
            self.contract_uref,
        ]
        options_count = len(list(filter(None, payment_options)))
        if options_count > 1:
            raise ValueError(
                "No more than one of payment, payment_hash, payment_name or payment_uref can be provided"
            )
        if not any(payment_options + [self.payment_amount]):
            raise ValueError("No payment options were found.")

    @staticmethod
    def from_args(args: Dict) -> "PaymentCode":
        wasm_file_path = args.get("payment")
        contract_hash = args.get("payment_hash")
        contract_name = args.get("payment_name")
        contract_uref = args.get("payment_uref")
        contract_args = PaymentCode._maybe_args_from_json(args.get("payment_args"))
        payment_amount = args.get("payment_amount")
        if not any(
            (
                wasm_file_path,
                contract_hash,
                contract_name,
                contract_uref,
                payment_amount,
            )
        ):
            payment_amount = DEFAULT_PAYMENT_AMOUNT
        payment_code = PaymentCode(
            wasm_file_path,
            contract_hash,
            contract_name,
            contract_uref,
            contract_args,
            payment_amount,
        )
        payment_code.validate()
        return payment_code

    def _only_args_encode(self) -> consensus.Deploy.Code:
        """ Payment allows call with only args to standard payment """
        if self.contract_args:
            return consensus.Deploy.Code(args=self.contract_args)
        else:
            payment_amount = self.payment_amount or DEFAULT_PAYMENT_AMOUNT
            payment_args = ABI.args([ABI.big_int("amount", int(payment_amount))])
            return consensus.Deploy.Code(args=payment_args)
