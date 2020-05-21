import os
import sys
import time
import ssl
from pathlib import Path

import pkg_resources
import functools
import base64

import google.protobuf.text_format
import google.protobuf.json_format
from . import abi
from . import casper_pb2 as casper
from . import consensus_pb2 as consensus
from . import crypto


def read_binary_file(file_name: str):
    with open(file_name, "rb") as f:
        return f.read()


def write_file(file_name, text):
    with open(file_name, "w") as f:
        f.write(text)


def write_binary_file(file_name, data):
    with open(file_name, "wb") as f:
        f.write(data)


def encode_base64(a: bytes):
    return str(base64.b64encode(a), "utf-8")


def guarded_command(function):
    """
    Decorator of functions that implement CLI commands.

    Occasionally the node can throw some exceptions instead of properly sending us a response,
    those will be deserialized on our end and rethrown by the gRPC layer.
    In this case we want to catch the exception and return a non-zero return code to the shell.

    :param function:  function to be decorated
    :return:
    """

    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            rc = function(*args, **kwargs)
            # Generally the CLI commands are assumed to succeed if they don't throw,
            # but they can also return a positive error code if they need to.
            if rc is not None:
                return rc
            return 0
        except Exception as e:
            print(str(e), file=sys.stderr)
            return 1

    return wrapper


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


def _serialize(o) -> bytes:
    return o.SerializeToString()


def _print_blocks(response, element_name="block"):
    count = 0
    for block in response:
        print(f"------------- {element_name} {count} ---------------")
        _print_block(block)
        print("-----------------------------------------------------\n")
        count += 1
    print("count:", count)


def _print_block(block):
    print(hexify(block))


def _read_version() -> str:
    version_path = Path(os.path.dirname(os.path.realpath(__file__))) / "VERSION"
    with open(version_path, "r") as f:
        return f.read().strip()


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
    payment_uref: bytes = None,
    session_hash: bytes = None,
    session_name: str = None,
    session_uref: bytes = None,
    ttl_millis: int = 0,
    dependencies: list = None,
    chain_name: str = None,
):
    """
    Create a protobuf deploy object. See deploy for description of parameters.
    """
    # Convert from hex to binary.
    if from_addr and len(from_addr) == 64:
        from_addr = bytes.fromhex(from_addr)

    if from_addr and len(from_addr) != 32:
        raise Exception(f"from_addr must be 32 bytes")

    if payment_amount:
        payment_args = abi.ABI.args([abi.ABI.big_int("amount", int(payment_amount))])

    session_options = (session, session_hash, session_name, session_uref)
    payment_options = (payment, payment_hash, payment_name, payment_uref)

    if len(list(filter(None, session_options))) != 1:
        raise TypeError(
            "deploy: exactly one of session, session_hash, session_name or session_uref must be provided"
        )

    if len(list(filter(None, payment_options))) > 1:
        raise TypeError(
            "deploy: only one of payment, payment_hash, payment_name or payment_uref can be provided"
        )

    # session_args must go to payment as well for now cause otherwise we'll get GASLIMIT error,
    # if payment is same as session:
    # https://github.com/CasperLabs/CasperLabs/blob/dev/casper/src/main/scala/io/casperlabs/casper/util/ProtoUtil.scala#L463
    body = consensus.Deploy.Body(
        session=_encode_contract(session_options, session_args),
        payment=_encode_contract(payment_options, payment_args),
    )

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

    return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)


def sign_deploy(deploy, public_key, private_key_file):
    # See if this is hex encoded
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
