from dataclasses import dataclass

from . import consensus_pb2 as consensus
import time

from casperlabs_client import crypto
from .contract import PaymentCode, SessionCode
from .crypto import private_to_public_key
from .io import read_pem_key


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
    from_: bytes
    payment_code: PaymentCode
    session_code: SessionCode
    public_key: str = None
    ttl_millis: int = 0
    dependencies: list = None
    chain_name: str = None
    private_key: str = None

    @staticmethod
    def from_args(args) -> "DeployData":
        """
        Build Deploy data class from command line arguments, this includes testing for combinations of args
        that are allowed or required.
        """
        from_ = getattr(args, "from", None)
        from_ = bytes.fromhex(from_) if from_ else None

        payment_code = PaymentCode.from_args(args)
        session_code = SessionCode.from_args(args)

        public_key = args.public_key
        ttl_millis = args.ttl_millis
        dependencies = args.dependencies
        if dependencies:
            dependencies = [bytes.fromhex(d) for d in dependencies]
        else:
            dependencies = []
        chain_name = args.chain_name or ""

        deploy = DeployData(
            from_,
            payment_code,
            session_code,
            public_key,
            ttl_millis,
            dependencies,
            chain_name,
        )
        if len(deploy.from_addr) != 32:
            raise Exception(
                "--from must be 32 bytes encoded as 64 characters long hexadecimal"
            )

        return deploy

    @property
    def public_key_to_use(self):
        if self.public_key:
            return crypto.read_pem_key(self.public_key)
        elif self.from_:
            return self.from_
        else:
            return crypto.private_to_public_key(self.private_key)

    @property
    def from_addr(self):
        """ Attempts to guess which from account we should use for the user. """
        # TODO: Should we require explicitly `from`?
        if self.from_:
            return self.from_
        elif self.public_key:
            return read_pem_key(self.public_key)
        elif self.private_key:
            return private_to_public_key(self.private_key)
        else:
            raise TypeError(
                "Unable to generate from address using `from`, `public_key`, or `private_key`."
            )

    def make_protobuf(self) -> consensus.Deploy:
        """
        Create a protobuf deploy object. See deploy for description of parameters.
        """
        if len(self.from_addr) != 32:
            raise Exception(f"from_addr must be 32 bytes")

        self.payment_code.validate()
        self.session_code.validate()

        body = consensus.Deploy.Body(
            session=self.session_code.encode(), payment=self.payment_code.encode()
        )
        body_hash = crypto.blake2b_hash((body.SerializeToString()))

        header = consensus.Deploy.Header(
            account_public_key=self.from_addr,
            timestamp=int(1000 * time.time()),
            body_hash=body_hash,
            ttl_millis=self.ttl_millis,
            dependencies=self.dependencies,
            chain_name=self.chain_name,
        )

        deploy_hash = crypto.blake2b_hash(header.SerializeToString())

        return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)
