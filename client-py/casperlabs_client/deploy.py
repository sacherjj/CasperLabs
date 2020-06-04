from dataclasses import dataclass
from typing import Dict

from . import consensus_pb2 as consensus, consts
import time

from casperlabs_client import crypto
from .contract import PaymentCode, SessionCode


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


@dataclass
class DeployData:
    from_addr: bytes
    payment_code: PaymentCode
    session_code: SessionCode
    account_hash: str = None
    ttl_millis: int = 0
    dependencies: list = None
    chain_name: str = None
    private_key: str = None

    @staticmethod
    def from_args(args: Dict) -> "DeployData":
        """
        Build Deploy data class from command line arguments, this includes testing for combinations of args
        that are allowed or required.
        """

        # `from` isn't good for dict creation, but used from CLI, so handle both `from` and `from_addr`
        from_addr = args.get("from", args.get("from_addr"))
        if from_addr and not isinstance(from_addr, bytes):
            from_addr = bytes.fromhex(from_addr)

        payment_code = PaymentCode.from_args(args)
        session_code = SessionCode.from_args(args)

        account_hash = args.get("account_hash")
        ttl_millis = args.get("ttl_millis")
        dependencies_hex = args.get("dependencies") or []
        dependencies = [bytes.fromhex(d) for d in dependencies_hex]
        chain_name = args.get("chain_name", "")
        private_key = args.get("private_key")

        deploy = DeployData(
            from_addr,
            payment_code,
            session_code,
            account_hash,
            ttl_millis,
            dependencies,
            chain_name,
            private_key,
        )
        if len(deploy.from_addr) != consts.ACCOUNT_HASH_LENGTH:
            raise Exception(
                "--from must be 32 bytes encoded as 64 characters long hexadecimal"
            )

        return deploy

    def make_protobuf(self) -> consensus.Deploy:
        """
        Create a protobuf deploy object. See deploy for description of parameters.
        """
        if len(self.from_addr) != 32:
            raise Exception(f"from_addr must be 32 bytes")

        self.payment_code.validate()
        self.session_code.validate()

        body = consensus.Deploy.Body(
            session=self.session_code.to_protobuf(),
            payment=self.payment_code.to_protobuf(),
        )
        body_hash = crypto.blake2b_hash((body.SerializeToString()))

        header = consensus.Deploy.Header(
            account_public_key_hash=self.account_hash,
            timestamp=int(1000 * time.time()),
            body_hash=body_hash,
            ttl_millis=self.ttl_millis,
            dependencies=self.dependencies,
            chain_name=self.chain_name,
        )

        deploy_hash = crypto.blake2b_hash(header.SerializeToString())

        return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)
