from casperlabs_client import consensus_pb2 as consensus

from casperlabs_client.decorators import guarded_command

from casperlabs_client.io import read_binary_file

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
    deploy = consensus.Deploy()
    file_contents = read_binary_file(args.deploy_path)
    deploy.ParseFromString(file_contents)
    casperlabs_client.send_deploy(deploy)
    print(f"Success! Deploy {deploy.deploy_hash.hex()} deployed")
