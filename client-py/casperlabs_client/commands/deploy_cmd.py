from ..arg_types import positive_integer

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
    [('--payment-package-hash',), dict(required=False, type=str, default=None, help='Hash of the stored package to be called in the payment; base16 encoded')],
    [('--payment-package-name',), dict(required=False, type=str, default=None, help='Name of the stored package (associated with the executing account) to be called in the payment')],
    [("--payment-entry-point",), dict(required=False, type=str, default=None, help="Name of the method that will be used when calling the payment contract.")],
    [("--payment-version",), dict(required=False, type=positive_integer, default=None, help="Version of the called payment contract.  Latest will be used by default")],
    [('--payment-args',), dict(required=False, type=str, help="""JSON encoded list of payment args, e.g.: '[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'""")],
    [('-s', '--session'), dict(required=False, type=str, default=None, help='Path to the file with session code')],
    [('--session-hash',), dict(required=False, type=str, default=None, help='Hash of the stored contract to be called in the session; base16 encoded')],
    [('--session-name',), dict(required=False, type=str, default=None, help='Name of the stored contract (associated with the executing account) to be called in the session')],
    [('--session-package-hash',), dict(required=False, type=str, default=None, help='Hash of the stored package to be called in the session; base16 encoded')],
    [('--session-package-name',), dict(required=False, type=str, default=None, help='Name of the stored package (associated with the executing account) to be called in the session')],
    [("--session-entry-point",), dict(required=False, type=str, default=None, help="Name of the method that will be used when calling the session contract.")],
    [("--session-version",), dict(required=False, type=positive_integer, default=None, help="Version of the called session contract. Latest will be used by default.")],
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
