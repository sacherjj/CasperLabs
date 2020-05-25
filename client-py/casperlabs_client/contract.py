import os

from casperlabs_client.io import read_binary_file
from . import consensus_pb2 as consensus
import pkg_resources


def _encode_contract(contract_options, contract_args):
    file_name, hash, name, uref = contract_options
    Code = consensus.Deploy.Code
    if file_name:
        return Code(wasm=read_binary_file(file_name), args=contract_args)
    if hash:
        return Code(hash=hash, args=contract_args)
    if name:
        return Code(name=name, args=contract_args)
    if uref:
        return Code(uref=uref, args=contract_args)
    return Code(args=contract_args)


def bundled_contract(file_name):
    """
    Return path to contract file bundled with the package.
    """
    p = pkg_resources.resource_filename(__name__, file_name)
    if not os.path.exists(p):
        raise Exception(f"Missing bundled contract {file_name} ({p})")
    return p
