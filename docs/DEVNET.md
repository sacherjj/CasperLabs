# DEVNET

## Quick Start

### Creating an account

* Go to [explorer.casperlabs.io](https://explorer.casperlabs.io/)
* Click [Sign-in] and complete the new user process
* Navigate to [Accounts](https://explorer.casperlabs.io/#/accounts) and click [Create Account]
  - Provide a name for your account and click [Save]; multiple key files will be written to disk
  - You will need these key files to interact with the network; give some consideration to where you save them
* Navigate to [Faucet](https://explorer.casperlabs.io/#/faucet), select your new account, and click [Request Tokens]
* The request will appear in the "Recent Faucet Requests" table; wait for the green check mark to appear in the "Status" column

### Deploying code

* [Install the CasperLabs client](INSTALL.md)
* [Compile a contract written in rust](CONTRACTS.md)
* Use the CasperLabs client `deploy` sub-command (example below)
  - `--nonce` begins at 1 and must increase by 1 with each deploy (i.e. sequence is 1, 2, 3, ...)
  - `--session` is the path to the compiled contract
  - `--private-key` is the path to the private key file downloaded from [explorer.casperlabs.io](https://explorer.casperlabs.io/) during account creation
```
casperlabs-client \
        --host deploy.casperlabs.io \
        deploy \
        --nonce 1 \
        --session my_contract.wasm \
        --private-key account.private.key
```

### Bonding

* [Install the CasperLabs node and client](INSTALL.md)
* Use the CasperLabs client `bond` sub-command (example below)
  - `--amount` is the amount of tokens you wish to bond
  - `--nonce` is preserved for each account; if you deployed code above, continue the sequence where you left off
  - `--private-key` is the path to the private key file downloaded from [explorer.casperlabs.io](https://explorer.casperlabs.io/) during account creation
```
casperlabs-client \
        --host deploy.casperlabs.io \
        bond \
        --amount 1000 \
        --nonce 2 \
        --private-key account.private.key
```
* [Run your node](NODE.md); include some specific command line flags for devnet:
  - Use `--bootstrap casperlabs://a605c8ddc4ed3dc9b881bfe006cc8175fb31e125@100.24.117.48?protocol=40400&discovery=40404` to connect to the devnet P2P network
  - Set `--casper-validator-private-key-path` be the path to the same private key you used for bonding
  - Use `--server-use-gossiping`
* Use the CasperLabs client with your node to deploy code and propose blocks on the devnet
```
casperlabs-client \
        --host localhost \
        deploy \
        --nonce 3 \
        --session my_other_contract.wasm \
        --private-key account.private.key

casperlabs-client \
        --host localhost \
        propose
```

### Unbonding

* Ensure you have the CasperLabs client installed
* Use the CasperLabs client `bond` sub-command (example below)
  - `--amount` is the amount of tokens you wish to unbond; this parameter is optional and if not present then all tokens will be unbonded
  - `--nonce` is preserved for each account, continue the sequence where you left off from your `bond` or last `deploy`
  - `--private-key` is the path to the private key file you used to `bond`
* Do not shutdown your running node until _after_ you have unbonded
```
casperlabs-client \
        --host deploy.casperlabs.io \
        unbond \
        --amount 500 \
        --nonce 4 \
        --private-key account.private.key
```

## Notes

* This quick start gives the simplest set of instructions for getting started on the CasperLabs devnet. More advanced users may wish to take other approaches to some of the steps listed above.
* Throughout this quick start the word "token" is used for convenience, however the units they correspond to have no monetary value whatsoever and do not necessarily correspond to the conception you may have of what a "token" is; for the purposes of the present devnet, "token" simply means "an indivisible unit which may be used for bonding onto the network".
