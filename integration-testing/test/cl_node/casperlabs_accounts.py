from dataclasses import dataclass


@dataclass
class Account:
    public_key: str
    private_key: str
    transfer_amount: float
    transfer_contract: str

    @property
    def public_key_hex(self):
        return ''.join([f"{int(ch):#04x}"[2:] for ch in self.public_key])


@dataclass
class Purse:
    purse_id: int
    balance: float


ACCOUNTS = {
    1: Account(public_key='11111111111111111111111111111111',
               private_key='',
               transfer_amount=100,
               transfer_contract='test_transfer_to_account_1_call.wasm'),
    2: Account(public_key='22222222222222222222222222222222',
               private_key='',
               transfer_amount=75,
               transfer_contract='test_transfer_to_account_2_call.wasm'),
    }
