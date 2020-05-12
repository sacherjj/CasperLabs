import os
import time
import ssl
import pkg_resources
import google.protobuf.text_format
import google.protobuf.json_format
from . import abi
from . import casper_pb2 as casper
from . import consensus_pb2 as consensus
from . import state_pb2 as state
from . import crypto
from semver import VersionInfo
from typing import Optional


def _read_binary(file_name: str):
    with open(file_name, "rb") as f:
        return f.read()


def hexify(o):
    """
    Convert protobuf message to text format with cryptographic keys and signatures in base 16.
    """
    return google.protobuf.text_format.MessageToString(o)


def jsonify(o):
    return google.protobuf.json_format.MessageToJson(o)


def bundled_contract(file_name):
    """
    Return path to contract file bundled with the package.
    """
    p = pkg_resources.resource_filename(__name__, file_name)
    if not os.path.exists(p):
        raise Exception(f"Missing bundled contract {file_name} ({p})")
    return p


def extract_common_name(certificate_file: str) -> str:
    cert_dict = ssl._ssl._test_decode_cert(certificate_file)
    return [t[0][1] for t in cert_dict["subject"] if t[0][0] == "commonName"][0]


# Note, there is also casper.StateQuery.KeyVariant.KEY_VARIANT_UNSPECIFIED,
# but it doesn't seem to have an official string representation
# ("key_variant_unspecified"? "unspecified"?) and is not used by the client.
STATE_QUERY_KEY_VARIANT = {
    "hash": casper.StateQuery.KeyVariant.HASH,
    "uref": casper.StateQuery.KeyVariant.UREF,
    "address": casper.StateQuery.KeyVariant.ADDRESS,
    "local": casper.StateQuery.KeyVariant.LOCAL,
}


def key_variant(key_type):
    """
    Returns query-state key variant.
    """
    variant = STATE_QUERY_KEY_VARIANT.get(key_type.lower(), None)
    if variant is None:
        raise ValueError(f"{key_type} is not a known query-state key type")
    return variant


def _encode_sem_ver(sem_ver) -> Optional[state.SemVer]:
    if sem_ver:
        return state.SemVer(
            major=sem_ver.major, minor=sem_ver.minor, patch=sem_ver.patch
        )
    return None


def _encode_contract(contract_options, contract_args, sem_ver=None, entry_point=None):
    print(f"utils.py::_encode_contract {contract_options}")
    file_name, hash_, name = contract_options
    Code = consensus.Deploy.Code
    if file_name:
        return Code(
            wasm=_read_binary(file_name), args=contract_args, entry_point=entry_point
        )
    elif hash_:
        sc = Code.StoredContract(hash=hash_, contract_version=_encode_sem_ver(sem_ver))
        return Code(stored_contract=sc, args=contract_args, entry_point=entry_point)
    elif name:
        sc = Code.StoredContract(name=name, conract_version=_encode_sem_ver(sem_ver))
        return Code(stored_contract=sc, args=contract_args, entry_point=entry_point)
    # If we fall through, this is standard payment
    return Code(args=contract_args)


def _serialize(o) -> bytes:
    return o.SerializeToString()


def _error_if_requires_sem_ver_and_missing(
    argument_type, hash_arg, name_arg, sem_ver_arg
):
    if (hash_arg or name_arg) and sem_ver_arg is None:
        raise TypeError(
            f"{argument_type}-sem-ver is required with {argument_type}-hash or {argument_type}-name."
        )


# TODO: Make this not a utility method.
def make_deploy(
    from_addr: bytes = None,
    gas_price: int = 10,
    payment: str = None,
    session: str = None,
    public_key: str = None,
    session_args: bytes = None,
    payment_args: bytes = None,
    payment_amount: int = None,
    payment_hash: bytes = None,
    payment_name: str = None,
    payment_entry_point: str = None,
    payment_sem_ver: VersionInfo = None,
    session_hash: bytes = None,
    session_name: str = None,
    session_entry_point: str = None,
    session_sem_ver: VersionInfo = None,
    ttl_millis: int = 0,
    dependencies: list = None,
    chain_name: str = None,
):
    """
    Create a protobuf deploy object. See deploy for description of parameters.
    """
    print("utils.py::make_deploy")
    # TODO: This should be in bytes form already?  Why is this here?
    # Convert from hex to binary.
    if from_addr and len(from_addr) == 64:
        from_addr = bytes.fromhex(from_addr)

    if from_addr and len(from_addr) != 32:
        raise Exception(f"from_addr must be 32 bytes")

    if payment_amount:
        payment_args = abi.ABI.args([abi.ABI.big_int("amount", int(payment_amount))])

    _error_if_requires_sem_ver_and_missing(
        "payment", payment_hash, payment_name, payment_sem_ver
    )
    _error_if_requires_sem_ver_and_missing(
        "session", session_hash, session_name, session_sem_ver
    )

    session_options = (session, session_hash, session_name)
    payment_options = (payment, payment_hash, payment_name)

    # TODO: Move error checking much earlier in the process
    if len(list(filter(None, session_options))) != 1:
        raise TypeError(
            "deploy: exactly one of session, session_hash, or session_name must be provided"
        )

    if len(list(filter(None, payment_options))) > 1:
        raise TypeError(
            "deploy: only one of payment, payment_hash, or payment_name can be provided"
        )

    body = consensus.Deploy.Body(
        session=_encode_contract(
            session_options, session_args, session_sem_ver, session_entry_point
        ),
        payment=_encode_contract(
            payment_options, payment_args, session_sem_ver, session_entry_point
        ),
    )
    print("End of utils.py::make_deploy")

    # TODO: How many places do we need this from_addr logic?  Can't this be one place only?
    # we are totally missing the generations from private_key here.
    header = consensus.Deploy.Header(
        account_public_key=from_addr
        or (public_key and crypto.read_pem_key(public_key)),
        timestamp=int(1000 * time.time()),
        gas_price=gas_price,
        body_hash=crypto.blake2b_hash(_serialize(body)),
        ttl_millis=ttl_millis,
        dependencies=dependencies and [bytes.fromhex(d) for d in dependencies] or [],
        chain_name=chain_name or "",
    )

    deploy_hash = crypto.blake2b_hash(_serialize(header))
    print("End of utils.py::make_deploy")
    return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)


def sign_deploy(deploy, public_key, private_key_file):
    # See if this is hex encoded
    print("utils.py::sign_deploy")
    try:
        public_key = bytes.fromhex(public_key)
    except TypeError:
        pass

    deploy.approvals.extend(
        [
            consensus.Approval(
                approver_public_key=public_key,
                signature=crypto.signature(private_key_file, deploy.deploy_hash),
            )
        ]
    )
    return deploy


def get_public_key(public_key, from_addr, private_key):
    return (
        (public_key and crypto.read_pem_key(public_key))
        or from_addr
        or crypto.private_to_public_key(private_key)
    )
