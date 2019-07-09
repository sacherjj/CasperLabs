from dataclasses import dataclass
from pathlib import Path
import os


@dataclass
class Account:
    public_key: str
    public_key_hex: str
    public_key_file: str
    private_key_file: str
    transfer_amount: float
    transfer_contract: str

    @staticmethod
    def int_key_to_hex(key):
        return ''.join([f"{int(ch):#04x}"[2:] for ch in key])


ACCOUNTS = {'genesis': Account(public_key='rnzYTWH/VWgGaRvmHmqyF3kZBWd6274IW4xUDZFug5M=',
                               public_key_hex='ae7cd84d61ff556806691be61e6ab217791905677adbbe085b8c540d916e8393',
                               public_key_file='account-public-genesis.pem',
                               private_key_file='account-private-genesis.pem', transfer_amount=0, transfer_contract=''),
            1: Account(public_key='nTm3+6R9B8Gvb3Ee/mBKESqzceLe77maYT0rPc37pBQ=',
                       public_key_hex='9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414',
                       public_key_file='account-public-1.pem', private_key_file='account-private-1.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_1_call.wasm'),
            2: Account(public_key='TnRuZ872N6B0A51gJH5cJgXZpdeOx6rEaoPM44EGnTU=',
                       public_key_hex='4e746e67cef637a074039d60247e5c2605d9a5d78ec7aac46a83cce381069d35',
                       public_key_file='account-public-2.pem', private_key_file='account-private-2.pem',
                       transfer_amount=750000, transfer_contract='test_transfer_to_account_2_call.wasm'),
            3: Account(public_key='WPdPKqEZ5dE8abOdA0Mw+tISbEc4mnsVBiGafwuuogk=',
                       public_key_hex='58f74f2aa119e5d13c69b39d034330fad2126c47389a7b1506219a7f0baea209',
                       public_key_file='account-public-3.pem', private_key_file='account-private-3.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_3_call.wasm'),
            4: Account(public_key='HKieoFdYwmHM1rbJHzF+wetS9WTXg0DUvdjx89LUSHY=',
                       public_key_hex='1ca89ea05758c261ccd6b6c91f317ec1eb52f564d78340d4bdd8f1f3d2d44876',
                       public_key_file='account-public-4.pem', private_key_file='account-private-4.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_4_call.wasm'),
            5: Account(public_key='6pb+YTBu7N5X4N9l7PlgNqhRXjNn56rdh7wSHCVi6gk=',
                       public_key_hex='ea96fe61306eecde57e0df65ecf96036a8515e3367e7aadd87bc121c2562ea09',
                       public_key_file='account-public-5.pem', private_key_file='account-private-5.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_5_call.wasm'),
            6: Account(public_key='AW9KTASW7GrDdctN0AI2GrGhHrJmbi/jlA/xHnJGqBg=',
                       public_key_hex='016f4a4c0496ec6ac375cb4dd002361ab1a11eb2666e2fe3940ff11e7246a818',
                       public_key_file='account-public-6.pem', private_key_file='account-private-6.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_6_call.wasm'),
            7: Account(public_key='3sYg/RiD/VzGcNcwdk/vjueR7GkW30zDHjbamiKKIDI=',
                       public_key_hex='dec620fd1883fd5cc670d730764fef8ee791ec6916df4cc31e36da9a228a2032',
                       public_key_file='account-public-7.pem', private_key_file='account-private-7.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_7_call.wasm'),
            8: Account(public_key='/wxF7teEy55PuRSIYlkS/URf/qwLE2XrWgRdyb1aba0=',
                       public_key_hex='ff0c45eed784cb9e4fb91488625912fd445ffeac0b1365eb5a045dc9bd5a6dad',
                       public_key_file='account-public-8.pem', private_key_file='account-private-8.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_8_call.wasm'),
            9: Account(public_key='17bidAlUbKdYBYxPDlvhPvmbYXAZpNBqFPgV/qLm654=',
                       public_key_hex='d7b6e27409546ca758058c4f0e5be13ef99b617019a4d06a14f815fea2e6eb9e',
                       public_key_file='account-public-9.pem', private_key_file='account-private-9.pem',
                       transfer_amount=1000000, transfer_contract='test_transfer_to_account_9_call.wasm'),
            10: Account(public_key='1dSZlgLdtJAMVAqSVPMptYxiIzyudXlbVN01va8d5IY=',
                        public_key_hex='d5d4999602ddb4900c540a9254f329b58c62233cae75795b54dd35bdaf1de486',
                        public_key_file='account-public-10.pem', private_key_file='account-private-10.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_10_call.wasm'),
            11: Account(public_key='V6pKFWo01y+qNwxiNrJwKP1GwpwwBjNbW/0tXsQQyKs=',
                        public_key_hex='57aa4a156a34d72faa370c6236b27028fd46c29c3006335b5bfd2d5ec410c8ab',
                        public_key_file='account-public-11.pem', private_key_file='account-private-11.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_11_call.wasm'),
            12: Account(public_key='o8LZPrTLf3z9IT13jV40PTwsEBHqOoUeXn8KabATvWA=',
                        public_key_hex='a3c2d93eb4cb7f7cfd213d778d5e343d3c2c1011ea3a851e5e7f0a69b013bd60',
                        public_key_file='account-public-12.pem', private_key_file='account-private-12.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_12_call.wasm'),
            13: Account(public_key='t9eYMD0bd3v6crI7r0ySZThFsNexHNchnILvuxVHq9U=',
                        public_key_hex='b7d798303d1b777bfa72b23baf4c92653845b0d7b11cd7219c82efbb1547abd5',
                        public_key_file='account-public-13.pem', private_key_file='account-private-13.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_13_call.wasm'),
            14: Account(public_key='5uzR1fai4CBkPUT2tYdhMCG+BQlH20uLBPonj8ot4tA=',
                        public_key_hex='e6ecd1d5f6a2e020643d44f6b587613021be050947db4b8b04fa278fca2de2d0',
                        public_key_file='account-public-14.pem', private_key_file='account-private-14.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_14_call.wasm'),
            15: Account(public_key='1JHV8xwVQgWIwSUGALgTjf2i3N+NiYKmiupnksQBlIk=',
                        public_key_hex='d491d5f31c15420588c1250600b8138dfda2dcdf8d8982a68aea6792c4019489',
                        public_key_file='account-public-15.pem', private_key_file='account-private-15.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_15_call.wasm'),
            16: Account(public_key='AjXd1PK+CboSXzcLET6/ZTD3GaJu06PTnWd81j57R4Q=',
                        public_key_hex='0235ddd4f2be09ba125f370b113ebf6530f719a26ed3a3d39d677cd63e7b4784',
                        public_key_file='account-public-16.pem', private_key_file='account-private-16.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_16_call.wasm'),
            17: Account(public_key='JPAmGHF0Kyw1drBLqEosKNmnjzmZJiTPBLx1sHveNQA=',
                        public_key_hex='24f0261871742b2c3576b04ba84a2c28d9a78f39992624cf04bc75b07bde3500',
                        public_key_file='account-public-17.pem', private_key_file='account-private-17.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_17_call.wasm'),
            18: Account(public_key='7HHcA2IOJMZCSQKgdw6no6nCQ01cVcFVVffpvIgzrUo=',
                        public_key_hex='ec71dc03620e24c6424902a0770ea7a3a9c2434d5c55c15555f7e9bc8833ad4a',
                        public_key_file='account-public-18.pem', private_key_file='account-private-18.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_18_call.wasm'),
            19: Account(public_key='37Bzq/uI6PIRruo0WqPC3J5mJkc50jpnStrdVFIeISM=',
                        public_key_hex='dfb073abfb88e8f211aeea345aa3c2dc9e66264739d23a674adadd54521e2123',
                        public_key_file='account-public-19.pem', private_key_file='account-private-19.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_19_call.wasm'),
            20: Account(public_key='0fc7cGfUAPIm7P2hdshKU0wviQJEfRC5kXkg9n3uoB8=',
                        public_key_hex='d1f73b7067d400f226ecfda176c84a534c2f8902447d10b9917920f67deea01f',
                        public_key_file='account-public-20.pem', private_key_file='account-private-20.pem',
                        transfer_amount=1000000, transfer_contract='test_transfer_to_account_20_call.wasm')}

GENESIS_ACCOUNT = ACCOUNTS['genesis'].public_key_hex


if __name__ == '__main__':
    # This generates the code for ACCOUNTS from the accounts key files.
    # I don't want to dynamically generate it each time, as transfer amounts and others are manual.
    # Might change to generate by key as we need.

    transfer_amount = [0, 1000000, 750000] + ([1000000] * 18)

    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != 'integration-testing':
        cur_path = cur_path.parent
    path = cur_path / 'resources' / 'accounts'
    accounts = {}
    rust_keys = {}
    for acct in ['genesis'] + list(range(1, 21)):

        with open(path / f'account-id-{acct}') as f:
            public_key = f.read().strip()

        with open(path / f'account-id-hex-{acct}') as f:
            public_key_hex = f.read().strip()

        # Converting hex string to list of ints for Rust contracts address
        if acct != 'genesis':
            rust_keys[acct] = [int(public_key_hex[i:i+2], 16) for i in range(0, len(public_key_hex), 2)]

        accounts[acct] = Account(public_key=public_key,
                                 public_key_hex=public_key_hex,
                                 public_key_file=f'account-public-{acct}.pem',
                                 private_key_file=f'account-private-{acct}.pem',
                                 transfer_amount=0 if acct == 'genesis' else transfer_amount[acct],
                                 transfer_contract='' if acct == 'genesis' else f'test_transfer_to_account_{acct}_call.wasm')
    print(f'ACCOUNTS = {accounts}')
    print('')

    print(f'Rust Code for it_common lib.rs:')
    for key, value in rust_keys.items():
        print(f'const ACCOUNT_{key}_ADDR: [u8;32] = {value};')
        print(f'const ACCOUNT_{key}_TRANSFER_AMOUNT: u32 = {transfer_amount[key]};')
        print('')
