from typing import Dict

from casperlabs_client import CasperLabsClient, consts, reformat
from casperlabs_client.decorators import guarded_command


TRANSFER_TO_ACCOUNT_WASM: str = "transfer_to_account_u512.wasm"
NAME: str = "transfer"
HELP: str = "Transfers funds between accounts"
OPTIONS = [
    [
        ("-a", "--amount"),
        dict(
            required=True,
            default=None,
            type=int,
            help="Amount of motes to transfer. Note: a mote is the smallest, indivisible unit of a token.",
        ),
    ],
    [
        ("-t", "--target-account"),
        dict(
            required=True,
            type=str,
            help="base64 or base16 representation of target account's public key",
        ),
    ],
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
        ("--algorithm",),
        dict(
            required=False,
            default=consts.ED25519_KEY_ALGORITHM,
            type=str,
            choices=consts.SUPPORTED_KEY_ALGORITHMS,
            help="Algorithm used for public key generation.",
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
    [
        ("--private-key",),
        dict(
            required=True,
            default=None,
            type=str,
            help="Path to the file with account private key (Ed25519)",
        ),
    ],
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.transfer(
        target_account=args.get("target_account"),
        amount=args.get("amount"),
        from_addr=args.get("from_addr"),
        payment=args.get("payment"),
        public_key=args.get("public_key"),
        private_key=args.get("private_key"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_uref=args.get("payment_uref"),
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
