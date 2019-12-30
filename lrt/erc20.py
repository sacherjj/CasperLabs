import os
import threading
from pathlib import Path

import casperlabs_client
from casperlabs_client import ABI

BASE_PATH = Path(os.path.dirname(os.path.abspath(__file__))).parent
ERC20_WASM = f"{BASE_PATH}/execution-engine/target/wasm32-unknown-unknown/release/erc20_smart_contract.wasm"


PROPOSE_LOCK = threading.Lock()

# At the beginning of a serialized version of Rust's Vec<u8>, first 4 bytes represent the size of the vector.
#
# Balances are 33 bytes arrays where:
#   - the first byte is "01";
#   - the rest is 32 bytes of the account's public key.
#
# Allowances are 64 bytes arrays where:
#   - the first 32 bytes are token owner's public key;
#   - the second 32 bytes are token spender's public key.
#
# Decimal version of "21 00 00 00" is 33.
# Decimal version of "40 00 00 00" is 64.
BALANCE_KEY_SIZE_HEX = "21000000"
ALLOWANCE_KEY_SIZE_HEX = "40000000"
BALANCE_BYTE = "01"


class Node:
    def __init__(
        self,
        host,
        port=casperlabs_client.DEFAULT_PORT,
        port_internal=casperlabs_client.DEFAULT_INTERNAL_PORT,
    ):
        self.host = host
        self.port = port
        self.port_internal = port_internal
        self.client = casperlabs_client.CasperLabsClient(
            host=self.host, port=port, port_internal=port_internal
        )


class Agent:
    def __init__(self, name):
        self.name = name
        print(f"Agent {str(self)}")

    def __str__(self):
        return f"{self.name}: {self.public_key_hex}"

    def on(self, node):
        return BoundAgent(self, node)

    @property
    def private_key(self):
        return f"{BASE_PATH}/hack/docker/keys/{self.name}/account-private.pem"

    @property
    def public_key(self):
        return f"{BASE_PATH}/hack/docker/keys/{self.name}/account-public.pem"

    @property
    def public_key_hex(self):
        with open(f"{BASE_PATH}/hack/docker/keys/{self.name}/account-id-hex") as f:
            return f.read().strip()


class BoundAgent:
    def __init__(self, agent, node):
        self.agent = agent
        self.node = node

    def call_contract(self, method):
        return method(self)


class SmartContract:
    def __init__(self, file_name, methods, propose_after_deploy=True):
        self.file_name = file_name
        self.methods = methods
        self.propose_after_deploy = propose_after_deploy

    def contract_hash_by_name(self, bound_agent, deployer, contract_name, block_hash):
        response = bound_agent.node.client.queryState(
            block_hash, key=deployer, path=contract_name, keyType="address"
        )
        return response.key.hash.hash

    def __getattr__(self, name):
        return self.method(name)

    def abi_encode_args(self, method_name, parameters, kwargs):
        args = [ABI.string_value("method", method_name)] + [
            parameters[p](p, kwargs[p]) for p in parameters
        ]
        return ABI.args(args)

    def method(self, name):
        if name not in self.methods:
            raise Exception(f"unknown method {name}")

        def method(**kwargs):
            parameters = self.methods[name]
            if set(kwargs.keys()) != set(parameters.keys()):
                raise Exception(
                    f"Arguments ({kwargs.keys()}) don't match parameters ({parameters.keys()}) of method {name}"
                )
            arguments = self.abi_encode_args(name, parameters, kwargs)
            arguments_string = f"{name}({','.join(f'{p}={type(kwargs[p]) == bytes and kwargs[p].hex() or kwargs[p]}' for p in parameters)})"

            def deploy_and_maybe_propose(bound_agent, **session_reference):
                with PROPOSE_LOCK:
                    kwargs = dict(
                        public_key=bound_agent.agent.public_key,
                        private_key=bound_agent.agent.private_key,
                        payment_amount=10000000,
                        session_args=arguments,
                    )
                    if session_reference:
                        kwargs.update(session_reference)
                    else:
                        kwargs["session"] = self.file_name
                    print(f"Call {arguments_string}")
                    _, deploy_hash = bound_agent.node.client.deploy(**kwargs)
                    if self.propose_after_deploy:
                        block_hash = bound_agent.node.client.propose().block_hash.hex()
                        for deploy_info in bound_agent.node.client.showDeploys(
                            block_hash
                        ):
                            if deploy_info.is_error:
                                raise Exception(
                                    f"Deploy {deploy_hash.hex()} [{arguments_string}] error_message: {deploy_info.error_message}"
                                )
                    return deploy_hash

            return deploy_and_maybe_propose

        return method


class DeployedERC20:
    def __init__(self, erc20, token_hash, proxy_hash):
        self.erc20 = erc20
        self.token_hash = token_hash
        self.proxy_hash = proxy_hash

    @classmethod
    def create(cls, deployer: BoundAgent, token_name: str):
        erc20 = ERC20(token_name)
        block_hash = last_block_hash(deployer.node)
        return DeployedERC20(
            erc20,
            erc20.token_hash(deployer, deployer.agent.public_key_hex, block_hash),
            erc20.proxy_hash(deployer, deployer.agent.public_key_hex, block_hash),
        )

    def balance(self, account_public_hex):
        def execute(bound_agent):
            key = f"{self.token_hash.hex()}:{BALANCE_KEY_SIZE_HEX}{BALANCE_BYTE}{account_public_hex}"
            block_hash_hex = last_block_hash(bound_agent.node)
            response = bound_agent.node.client.queryState(
                block_hash_hex, key=key, path="", keyType="local"
            )
            return int(response.big_int.value)

        return execute

    def transfer(self, sender_private_key, recipient_public_key_hex, amount):
        def execute(bound_agent):
            return self.erc20.method("transfer")(
                erc20=self.token_hash,
                recipient=bytes.fromhex(recipient_public_key_hex),
                amount=amount,
            )(bound_agent, private_key=sender_private_key, session_hash=self.proxy_hash)

        return execute


class ERC20(SmartContract):
    methods = {
        "deploy": {"token_name": ABI.string_value, "initial_balance": ABI.big_int},
        "transfer": {
            "erc20": ABI.bytes_value,
            "recipient": ABI.bytes_value,
            "amount": ABI.big_int,
        },
        "approve": {
            "erc20": ABI.bytes_value,
            "recipient": ABI.bytes_value,
            "amount": ABI.big_int,
        },
        "transfer_from": {
            "erc20": ABI.bytes_value,
            "owner": ABI.bytes_value,
            "recipient": ABI.bytes_value,
            "amount": ABI.big_int,
        },
    }

    def __init__(self, token_name):
        super().__init__(ERC20_WASM, ERC20.methods)
        self.token_name = token_name
        self.proxy_name = "erc20_proxy"

    def abi_encode_args(self, method_name, parameters, kwargs):
        # When using proxy make sure that token_hash ('erc20') is the first argument
        args = (
            [parameters[p](p, kwargs[p]) for p in parameters if p == "erc20"]
            + [ABI.string_value("method", method_name)]
            + [parameters[p](p, kwargs[p]) for p in parameters if p != "erc20"]
        )
        return ABI.args(args)

    def proxy_hash(self, bound_agent, deployer_public_hex, block_hash):
        return self.contract_hash_by_name(
            bound_agent, deployer_public_hex, self.proxy_name, block_hash
        )

    def token_hash(self, bound_agent, deployer_public_hex, block_hash):
        return self.contract_hash_by_name(
            bound_agent, deployer_public_hex, self.token_name, block_hash
        )

    def deploy(self, initial_balance=None):
        def execute(bound_agent):
            deploy_hash = self.method("deploy")(
                token_name=self.token_name, initial_balance=initial_balance
            )(bound_agent)
            deploy_hash = deploy_hash
            block_hash = last_block_hash(bound_agent.node)
            return DeployedERC20(
                self,
                self.token_hash(
                    bound_agent, bound_agent.agent.public_key_hex, block_hash
                ),
                self.proxy_hash(
                    bound_agent, bound_agent.agent.public_key_hex, block_hash
                ),
            )

        return execute


def last_block_hash(node):
    return next(node.client.showBlocks(1)).summary.block_hash.hex()


def transfer_clx(sender, recipient_public_hex, amount):
    args = [
        "transfer",
        "--private-key",
        sender.agent.private_key,
        "--payment-amount",
        1000000000,
        "--from",
        sender.agent.public_key_hex,
        "-t",
        recipient_public_hex,
        "-a",
        amount,
    ]
    str_args = " ".join(map(str, args))
    print(str_args)
    rc = sender.node.client.cli(*args)
    if rc != 0:
        raise Exception(f"FAILED: {str_args} => {rc}")
    block_hash = sender.node.client.propose().block_hash.hex()
    for deploy_info in sender.node.client.showDeploys(block_hash):
        if deploy_info.is_error:
            raise Exception(f"FAILED: {str_args} => {deploy_info.error_message}")
