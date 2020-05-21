from .. import consensus_pb2 as consensus

from casperlabs_client.utils import guarded_command

NAME: str = "send-deploy"
HELP: str = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending on "
    "the configuration of the Casper instance."
)
OPTIONS = [
    [
        ("-i", "--deploy-path"),
        dict(required=False, default=None, help="Path to the file with signed deploy."),
    ]
]


@guarded_command
def method(casperlabs_client, args):
    # TODO: args should be replaced with dataclass
    deploy = consensus.Deploy()
    with open(args.deploy_path, "rb") as f:
        deploy.ParseFromString(f.read())
        casperlabs_client.send_deploy(deploy)
    print(f"Success! Deploy {deploy.deploy_hash.hex()} deployed")
