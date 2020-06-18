import os
import time
import ssl
import pkg_resources
import google.protobuf.text_format
import google.protobuf.json_format
from . import abi
from . import casper_pb2 as casper
from . import consensus_pb2 as consensus
from . import crypto
from . import ipc_pb2 as ipc


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


def _encode_contract(contract_options, contract_args, version=None, entry_point=None):
    print(f"utils.py::_encode_contract {contract_options}")
    file_name, contract_hash, contract_name, package_hash, package_name = (
        contract_options
    )
    if file_name:
        deploy_code = ipc.DeployCode(code=_read_binary(file_name), args=contract_args)
        return ipc.DeployPayload(deploy_code=deploy_code)
    elif contract_hash:
        sch = ipc.StoredContractHash(
            hash=contract_hash, args=contract_args, entry_point_name=entry_point
        )
        return ipc.DeployPayload(stored_contract_hash=sch)
    elif contract_name:
        scn = ipc.StoredContractName(
            name=contract_name, args=contract_args, entry_point_name=entry_point
        )
        return ipc.DeployPayload(stored_contract_name=scn)
    elif package_hash:
        sph = ipc.StoredContractPackageHash(
            hash=package_hash,
            version=version,
            entry_point_name=entry_point,
            args=contract_args,
        )
        return ipc.DeployPayload(stored_package_by_hash=sph)
    elif package_name:
        scp = ipc.StoredContractPackage(
            name=package_name,
            version=version,
            entry_point_name=entry_point,
            args=contract_args,
        )
        return ipc.DeployPayload(stored_package_by_name=scp)
    # If we fall through, this is standard payment
    # TODO: Is this still valid with contract headers?
    return ipc.DeployPayload(deploy_code=ipc.DeployCode(args=contract_args))


def _serialize(o) -> bytes:
    return o.SerializeToString()


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
    payment_package_hash: bytes = None,
    payment_package_name: str = None,
    payment_entry_point: str = None,
    payment_version: int = None,
    session_hash: bytes = None,
    session_name: str = None,
    session_package_hash: bytes = None,
    session_package_name: str = None,
    session_entry_point: str = None,
    session_version: int = None,
    ttl_millis: int = 0,
    dependencies: list = None,
    chain_name: str = None,
):
    """
    Create a protobuf deploy object. See deploy for description of parameters.
    """
    # TODO: This should be in bytes form already?  Why is this here?
    # Convert from hex to binary.
    if from_addr and len(from_addr) == 64:
        from_addr = bytes.fromhex(from_addr)

    if from_addr and len(from_addr) != 32:
        raise Exception("from_addr must be 32 bytes")

    if payment_amount:
        payment_args = abi.ABI.args([abi.ABI.big_int("amount", int(payment_amount))])

    session_options = (
        session,
        session_hash,
        session_name,
        session_package_hash,
        session_package_name,
    )
    payment_options = (
        payment,
        payment_hash,
        payment_name,
        payment_package_hash,
        payment_package_name,
    )

    # TODO: Move error checking much earlier in the process
    if len(list(filter(None, session_options))) != 1:
        raise TypeError(
            "deploy: exactly one of session, session_hash, session_name, "
            "session_package_hash, or session_package_name must be provided"
        )

    if len(list(filter(None, payment_options))) > 1:
        raise TypeError(
            "deploy: only one of payment, payment_hash, payment_name, "
            "payment_package_hash, or payment_package_name can be provided"
        )

    body = consensus.Deploy.Body(
        session=_encode_contract(
            session_options, session_args, session_version, session_entry_point
        ),
        payment=_encode_contract(
            payment_options, payment_args, payment_version, payment_entry_point
        ),
    )

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
