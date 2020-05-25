from . import consensus_pb2 as consensus, abi
import time

from casperlabs_client import crypto
from casperlabs_client.contract import _encode_contract


def _select_public_key_to_use(public_key, from_addr, private_key):
    if public_key:
        return crypto.read_pem_key(public_key)
    elif from_addr:
        return from_addr
    else:
        return crypto.private_to_public_key(private_key)


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
        body_hash=crypto.blake2b_hash(body.SerializeToString()),
        ttl_millis=ttl_millis,
        dependencies=dependencies and [bytes.fromhex(d) for d in dependencies] or [],
        chain_name=chain_name or "",
    )

    deploy_hash = crypto.blake2b_hash(header.SerializeToString())

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


#
# @dataclass
# class Deploy:
#     from_: bytes = None
#     gas_price: int = 10
#     payment: str = None
#     session: str = None
#     public_key: str = None
#     session_args: bytes = None
#     payment_args: bytes = None
#     payment_amount: int = None
#     payment_hash: bytes = None
#     payment_name: str = None
#     payment_sem_ver: VersionInfo = None
#     session_hash: bytes = None
#     session_name: str = None
#     session_sem_ver: VersionInfo = None
#     ttl_millis: int = 0
#     dependencies: list = None
#     chain_name: str = None
#     private_key: str = None
#
#     @staticmethod
#     def from_args(args) -> 'Deploy':
#         # TODO: Parse and convert args for loaded Deploy dataclass
#         pass
#
#     @property
#     def from_addr(self):
#         """ Attempts to guess which from account we should use for the user. """
#         # TODO: Should this be required explicitly `from`?
#         if self.from_:
#             return self.from_
#         elif self.public_key:
#             return read_pem_key(self.public_key)
#         elif self.private_key:
#             return private_to_public_key(self.private_key)
#         else:
#             raise TypeError("Unable to generate from address using `from`, `public_key`, or `private_key`.")
#
