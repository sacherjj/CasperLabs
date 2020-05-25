from casperlabs_client import consts
from casperlabs_client.abi import ABI
from casperlabs_client.consts import DEFAULT_PAYMENT_AMOUNT
from casperlabs_client.crypto import read_pem_key, private_to_public_key
from casperlabs_client.utils import hexify
from casperlabs_client.decorators import guarded_command


NAME = "deploy"
HELP = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending "
    "on the configuration of the Casper instance."
)

OPTIONS = [
    [
        ("-f", "--from"),
        dict(
            required=False,
            type=str,
            help="The public key of the account which is the context of this deployment, base16 encoded.",
        ),
    ],
    [
        ("--chain-name",),
        dict(
            required=False,
            type=str,
            help="Name of the chain to optionally restrict the deploy from being accidentally included anywhere else.",
        ),
    ],
    [
        ("--dependencies",),
        dict(
            required=False,
            nargs="+",
            default=None,
            help="List of deploy hashes (base16 encoded) which must be executed before this deploy.",
        ),
    ],
    [
        ("--payment-amount",),
        dict(
            required=False,
            type=int,
            default=None,
            help=(
                "Standard payment amount. Use this with the default payment, or override with --payment-args "
                "if custom payment code is used. By default --payment-amount is set to 10000000"
            ),
        ),
    ],
    # TODO - This was only for integration testing and should be removed from client.
    [
        ("--gas-price",),
        dict(
            required=False,
            type=int,
            default=10,
            help="The price of gas for this transaction in units dust/gas. Must be positive integer.",
        ),
    ],
    [
        ("-p", "--payment"),
        dict(
            required=False,
            type=str,
            default=None,
            help="Path to the file with payment code",
        ),
    ],
    [
        ("--payment-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored contract to be called in the payment; base16 encoded",
        ),
    ],
    [
        ("--payment-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored contract (associated with the executing account) to be called in the payment",
        ),
    ],
    [
        ("--payment-uref",),
        dict(
            required=False,
            type=str,
            default=None,
            help="URef of the stored contract to be called in the payment; base16 encoded",
        ),
    ],
    [
        ("-s", "--session"),
        dict(
            required=False,
            type=str,
            default=None,
            help="Path to the file with session code",
        ),
    ],
    [
        ("--session-hash",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Hash of the stored contract to be called in the session; base16 encoded",
        ),
    ],
    [
        ("--session-name",),
        dict(
            required=False,
            type=str,
            default=None,
            help="Name of the stored contract (associated with the executing account) to be called in the session",
        ),
    ],
    [
        ("--session-uref",),
        dict(
            required=False,
            type=str,
            default=None,
            help="URef of the stored contract to be called in the session; base16 encoded",
        ),
    ],
    [
        ("--session-args",),
        dict(
            required=False,
            type=str,
            help="""JSON encoded list of session args, e.g.: '[{"name": "amount", "value": {"long_value": 123456}}]'""",
        ),
    ],
    [
        ("--payment-args",),
        dict(
            required=False,
            type=str,
            help=(
                "JSON encoded list of payment args, e.g.: "
                '[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'
            ),
        ),
    ],
    [
        ("--ttl-millis",),
        dict(
            required=False,
            type=int,
            help="""Time to live. Time (in milliseconds) that the deploy will remain valid for.'""",
        ),
    ],
    [
        ("-w", "--wait-for-processed"),
        dict(action="store_true", help="Wait for deploy status PROCESSED or DISCARDED"),
    ],
    [
        ("--timeout-seconds",),
        dict(type=int, default=consts.STATUS_TIMEOUT, help="Timeout in seconds"),
    ],
    [
        ("--public-key",),
        dict(
            required=False,
            default=None,
            type=str,
            help="Path to the file with account public key (Ed25519)",
        ),
    ],
    [
        ("--account-hash",),
        dict(
            required=False,
            type=str,
            help="Account hash based on account public key. Generated with `account-hash` command.",
        ),
    ],
]
OPTIONS_WITH_PRIVATE = [
    [
        ("--private-key",),
        dict(
            required=True,
            default=None,
            type=str,
            help="Path to the file with account private key (Ed25519)",
        ),
    ]
] + OPTIONS


# TODO: Replace with staticmethod constructor in commands/deploy_cmd.py
def process_kwargs(args, private_key_accepted=True):
    from_addr = (
        getattr(args, "from")
        and bytes.fromhex(getattr(args, "from"))
        or getattr(args, "public_key")
        and read_pem_key(args.public_key)
        or private_to_public_key(args.private_key)
    )
    if from_addr and len(from_addr) != 32:
        raise Exception(
            "--from must be 32 bytes encoded as 64 characters long hexadecimal"
        )

    if not (args.payment_amount or args.payment_args):
        args.payment_amount = DEFAULT_PAYMENT_AMOUNT

    if args.payment_amount:
        args.payment_args = ABI.args_to_json(
            ABI.args([ABI.big_int("amount", int(args.payment_amount))])
        )

    d = dict(
        from_addr=from_addr,
        gas_price=args.gas_price,
        payment=args.payment,
        session=args.session,
        public_key=args.public_key or None,
        session_args=args.session_args
        and ABI.args_from_json(args.session_args)
        or None,  # TODO: Why would args_from_json ever return None or False?
        payment_args=args.payment_args
        and ABI.args_from_json(args.payment_args)
        or None,  # TODO: Why would args_from_json ever return None or False?
        payment_hash=args.payment_hash and bytes.fromhex(args.payment_hash),
        payment_name=args.payment_name,
        payment_uref=args.payment_uref and bytes.fromhex(args.payment_uref),
        session_hash=args.session_hash and bytes.fromhex(args.session_hash),
        session_name=args.session_name,
        session_uref=args.session_uref and bytes.fromhex(args.session_uref),
        ttl_millis=args.ttl_millis,
        dependencies=args.dependencies,
        chain_name=args.chain_name,
    )
    if private_key_accepted:
        d["private_key"] = args.private_key or None
    return d


@guarded_command
def method(casperlabs_client, args):
    # TODO: args should be replaced with dataclass
    kwargs = process_kwargs(args)
    deploy_hash = casperlabs_client.deploy(**kwargs)
    print(f"Success! Deploy {deploy_hash} deployed")
    if args.wait_for_processed:
        deploy_info = casperlabs_client.show_deploy(
            deploy_hash,
            full_view=False,
            wait_for_processed=args.wait_for_processed,
            timeout_seconds=args.timeout_seconds,
        )
        print(hexify(deploy_info))


# TODO: Encode proper logic for required arguments and testing of arguments at first parsing of dataclass
# Argument dependency tree to represent in help
# session (WASM file)
#   or
# session-hash or session-name
#   session-entry-point or 'call' will be used.
#   session-sem-ver or latest will be used.
