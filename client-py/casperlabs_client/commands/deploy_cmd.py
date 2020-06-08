from typing import Dict

from casperlabs_client import consts, reformat, CasperLabsClient
from casperlabs_client.decorators import guarded_command

NAME: str = "deploy"
HELP: str = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending "
    "on the configuration of the Casper instance."
)

OPTIONS = [
    [
        ("-f", "--from"),
        dict(
            required=True,
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


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.deploy(
        from_addr=args.get("from"),
        payment=args.get("payment"),
        session=args.get("session"),
        public_key=args.get("public_key"),
        private_key=args.get("private_key"),
        session_args=args.get("session_args"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_uref=args.get("payment_uref"),
        session_hash=args.get("session_hash"),
        session_name=args.get("session_name"),
        session_uref=args.get("session_uref"),
        ttl_millis=args.get("ttl_millis"),
        dependencies=args.get("dependencies"),
        chain_name=args.get("chain_name"),
    )
    print(f"Success! Deploy {deploy_hash} deployed")
    if args.get("wait_for_processed", False):
        deploy_info = casperlabs_client.show_deploy(
            deploy_hash,
            full_view=False,
            wait_for_processed=True,
            timeout_seconds=args.get("timeout_seconds", consts.STATUS_TIMEOUT),
        )
        print(reformat.hexify(deploy_info))
