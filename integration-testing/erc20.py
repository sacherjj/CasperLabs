import casperlabs_client
from casperlabs_client import ABI

BASE_PATH = "/home/piotr/CasperLabs"
ERC20_WASM = f"{BASE_PATH}/execution-engine/target/wasm32-unknown-unknown/release/erc20_smart_contract.wasm"
TOTAL_SUPPLY = 20000
TOKEN_NAME = "ABC"


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
    def __init__(self, host):
        # self.port = port  # TODO
        self.host = host
        self.client = casperlabs_client.CasperLabsClient(host=self.host)


class Agent:
    def __init__(self, name):
        self.name = name

    def __str__(self):
        return self.name

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
    def __init__(self, file_name, methods):
        self.file_name = file_name
        self.methods = methods

    def contract_hash_by_name(self, bound_agent, deployer, contract_name, block_hash):
        response = bound_agent.node.client.queryState(
            block_hash, key=deployer, path=contract_name, keyType="address"
        )
        return response.key.hash.hash

    def __getattr__(self, name):
        return self.method(name)

    def method(self, name):
        if name not in self.methods:
            raise Exception(f"unknown method {name}")

        def method(**kwargs):
            parameters = self.methods[name]
            if set(kwargs.keys()) != set(parameters.keys()):
                raise Exception(
                    f"Arguments ({kwargs.keys()}) don't match parameters ({parameters.keys()}) of method {name}"
                )
            arguments = ABI.args(
                [ABI.string_value("method", name)]
                + [parameters[p](p, kwargs[p]) for p in parameters]
            )

            arguments_string = (
                f"{name}({','.join(f'{p}={kwargs[p]}' for p in parameters)})"
            )

            def deploy_and_propose(bound_agent, **session_reference):
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
                _, deploy_hash = bound_agent.node.client.deploy(**kwargs)
                block_hash = bound_agent.node.client.propose().block_hash.hex()
                for deployInfo in bound_agent.node.client.showDeploys(block_hash):
                    if deployInfo.is_error:
                        raise Exception(
                            f"Deploy {deploy_hash.hex()} [{arguments_string}] error_message: {deployInfo.error_message}"
                        )
                return deploy_hash

            return deploy_and_propose

        return method


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

    def proxy_hash(self, bound_agent, deployer_public_hex, block_hash):
        return self.contract_hash_by_name(
            bound_agent, deployer_public_hex, self.proxy_name, block_hash
        )

    def token_hash(self, bound_agent, deployer_public_hex, block_hash):
        return self.contract_hash_by_name(
            bound_agent, deployer_public_hex, self.token_name, block_hash
        )

    def deploy(self, initial_balance=None):
        deploy = self.method("deploy")(
            token_name=self.token_name, initial_balance=initial_balance
        )

        def execute(bound_agent):
            deploy_hash = deploy(bound_agent)
            return deploy_hash.hex()

        return execute

    def balance(self, deployer_public_hex, account_public_hex, block_hash_hex):
        def execute(bound_agent):
            token_hash = self.token_hash(
                bound_agent, deployer_public_hex, block_hash_hex
            )
            key = f"{token_hash.hex()}:{BALANCE_KEY_SIZE_HEX}{BALANCE_BYTE}{account_public_hex}"
            response = bound_agent.node.client.queryState(
                block_hash_hex, key=key, path="", keyType="local"
            )
            return int(response.big_int.value)

        return execute

    def transfer(
        self,
        deployer_public_hex,
        sender_private_key,
        recipient_public_key_hex,
        amount,
        block_hash_hex,
    ):
        method_transfer = self.method("transfer")

        def execute(bound_agent):
            token_hash = self.token_hash(
                bound_agent, deployer_public_hex, block_hash_hex
            )
            proxy_hash = self.proxy_hash(
                bound_agent, deployer_public_hex, block_hash_hex
            )
            transfer = method_transfer(
                erc20=token_hash,
                recipient=bytes.fromhex(recipient_public_key_hex),
                amount=amount,
            )
            response = transfer(
                bound_agent, private_key=sender_private_key, session_hash=proxy_hash
            )
            return response  # TODO

        return execute


node = Node("localhost")

agent0 = Agent("account-0")
agent1 = Agent("account-1")
agent2 = Agent("account-2")
faucet = Agent("faucet-account")

abc = ERC20("ABC")

boss = faucet.on(node)

boss.call_contract(abc.deploy(initial_balance=TOTAL_SUPPLY))
print(f"Deployed ERC20")
block_hash_hex = next(node.client.showBlocks(1)).summary.block_hash.hex()
# import pdb; pdb.set_trace()
balance = boss.call_contract(
    abc.balance(faucet.public_key_hex, faucet.public_key_hex, block_hash_hex)
)
print(f"{faucet} balance={balance}")

boss.call_contract(
    abc.transfer(
        deployer_public_hex=faucet.public_key_hex,
        sender_private_key=faucet.private_key,
        recipient_public_key_hex=agent0.public_key_hex,
        amount=50,
        block_hash_hex=block_hash_hex,
    )
)

balance = boss.call_contract(
    abc.balance(agent0.public_key_hex, agent0.public_key_hex, block_hash_hex)
)
print(f"{agent0} balance={balance}")
