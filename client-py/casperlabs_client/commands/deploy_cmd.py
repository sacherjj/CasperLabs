from ..arg_types import sem_ver

COMMAND_NAME = "deploy"
HELP_TEXT = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending "
    "on the configuration of the Casper instance."
)
STATUS_CHECK_DELAY = 0.5
STATUS_TIMEOUT = 180  # 3 minutes

# fmt: off
DEPLOY_OPTIONS = [
    [('-f', '--from'), dict(required=False, type=str, help="The public key of the account which is the context of this deployment, base16 encoded.")],
    [('--chain-name',), dict(required=False, type=str, help="Name of the chain to optionally restrict the deploy from being accidentally included anywhere else.")],
    [('--dependencies',), dict(required=False, nargs="+", default=None, help="List of deploy hashes (base16 encoded) which must be executed before this deploy.")],
    [('--payment-amount',), dict(required=False, type=int, default=None, help="Standard payment amount. Use this with the default payment, or override with --payment-args if custom payment code is used. By default --payment-amount is set to 10000000")],
    [('--gas-price',), dict(required=False, type=int, default=10, help='The price of gas for this transaction in units dust/gas. Must be positive integer.')],
    [('-p', '--payment'), dict(required=False, type=str, default=None, help='Path to the file with payment code')],
    [('--payment-hash',), dict(required=False, type=str, default=None, help='Hash of the stored contract to be called in the payment; base16 encoded')],
    [('--payment-name',), dict(required=False, type=str, default=None, help='Name of the stored contract (associated with the executing account) to be called in the payment')],
    [("--payment-entry-point",), dict(required=False, type=str, default=None, help="Name of the method that will be used when calling the payment contract.")],
    [("--payment-sem-ver",), dict(required=False, type=sem_ver, default=None, help="Semantic version of the called payment contract. Matches the pattern `major.minor.patch`.")],
    [('--payment-args',), dict(required=False, type=str, help="""JSON encoded list of payment args, e.g.: '[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'""")],
    [('-s', '--session'), dict(required=False, type=str, default=None, help='Path to the file with session code')],
    [('--session-hash',), dict(required=False, type=str, default=None, help='Hash of the stored contract to be called in the session; base16 encoded')],
    [('--session-name',), dict(required=False, type=str, default=None, help='Name of the stored contract (associated with the executing account) to be called in the session')],
    [("--session-entry-point",), dict(required=False, type=str, default=None, help="Name of the method that will be used when calling the session contract.")],
    [("--session-sem-ver",), dict(required=False, type=sem_ver, default=None, help="Semantic version of the called session contract. Matches the pattern `major.minor.patch`.")],
    [('--session-args',), dict(required=False, type=str, help="""JSON encoded list of session args, e.g.: '[{"name": "amount", "value": {"long_value": 123456}}]'""")],
    [('--ttl-millis',), dict(required=False, type=int, help="""Time to live. Time (in milliseconds) that the deploy will remain valid for.'""")],
    [('-w', '--wait-for-processed'), dict(action='store_true', help='Wait for deploy status PROCESSED or DISCARDED')],
    [('--timeout-seconds',), dict(type=int, default=STATUS_TIMEOUT, help='Timeout in seconds')],
    [('--public-key',), dict(required=False, default=None, type=str, help='Path to the file with account public key (Ed25519)')]
]
DEPLOY_OPTIONS_PRIVATE = [
    [('--private-key',), dict(required=True, default=None, type=str, help='Path to the file with account private key (Ed25519)')]
] + DEPLOY_OPTIONS
# fmt:on

# TODO: Encode proper logic for required arguments and testing of arguments at first parsing of dataclass
# Argument dependency tree to represent in help
# session (WASM file)
#   or
# session-hash or session-name
#   session-entry-point or 'call' will be used.
#   session-sem-ver or latest will be used.


# TODO: Verify that we can move to Python 3.7 to use dataclasses to clean up
# from dataclasses import dataclass
# from semver import VersionInfo
#
# from casperlabs_client.crypto import read_pem_key, private_to_public_key
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
