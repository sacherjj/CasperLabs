import casperlabs_client
from casperlabs_client import ABI

BASE_PATH = "/home/piotr/CasperLabs"
PRIVATE_KEY = f"{BASE_PATH}/hack/docker/keys/faucet-account/account-private.pem"
PUBLIC_KEY = f"{BASE_PATH}/hack/docker/keys/faucet-account/account-public.pem"
ERC20_WASM = f"{BASE_PATH}/execution-engine/target/wasm32-unknown-unknown/release/erc20_smart_contract.wasm"
TOTAL_SUPPLY = 20000
TOKEN_NAME = "ABC"


class SmartContract:
    def __init__(self, file_name, methods, client):
        self.file_name = file_name
        self.methods = methods
        self.client = client

    def __getattr__(self, name):
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
            _, deploy_hash = client.deploy(
                public_key=PUBLIC_KEY,
                private_key=PRIVATE_KEY,
                payment_amount=10000000,
                session=self.file_name,
                session_args=arguments,
            )
            block_hash = self.client.propose().block_hash.hex()
            for deployInfo in client.showDeploys(block_hash):
                if deployInfo.is_error:
                    raise Exception(
                        f"Deploy {deploy_hash} error_message: {deployInfo.error_message}"
                    )

        return method


class ERC20(SmartContract):
    methods = {
        "deploy": {"token_name": ABI.string_value, "initial_balance": ABI.big_int}
    }

    def __init__(self, client):
        super().__init__(ERC20_WASM, ERC20.methods, client)


client = casperlabs_client.CasperLabsClient(host="localhost")

abc = ERC20(client)
abc.deploy(token_name=TOKEN_NAME, initial_balance=TOTAL_SUPPLY)


print(f"Deployed ERC20")
