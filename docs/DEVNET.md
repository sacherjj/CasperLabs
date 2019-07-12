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

#### Checking the balance of an account

* [Install the CasperLabs client](INSTALL.md)
* Use the `balance` sub-command (see example below)
  * `--address` is the hex-encoded public key of the account to query
  * `--block-hash` the hex-encoded hash of the block where the balance should be queried
```
casperlabs-client \
        --host deploy.casperlabs.io \
        balance \
        --address b9ae114d6093646ed4be6f7fe1f7e5533a5d52a3351f3f18030ea82b3d915d6b \
        --block-hash ef6d4c66a29d833de462fbb7fd35227cbc3849b36872940c852727f668d6993f
```

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

* Follow instructions in [NODE.md](NODE.md) for connecting to the CasperLabs network
* Once bonded, you can use the CasperLabs client with your local node to deploy code and propose blocks on the devnet
  - See [CONTRACTS.md](CONTRACTS.md) for details
```
casperlabs-client \
        --host localhost \
        deploy \
        --nonce <nonce> \
        --session <path-to-wasm> \
        --private-key <path-to-account-private-key>

casperlabs-client \
        --host localhost \
        propose
```

### Unbonding

* Follow instructions in [NODE.md](NODE.md) for stopping a bonded validator

## Notes

* This quick start gives the simplest set of instructions for getting started on the CasperLabs devnet. More advanced users may wish to take other approaches to some of the steps listed above.
* Throughout this quick start the word "token" is used for convenience, however the units they correspond to have no monetary value whatsoever and do not necessarily correspond to the conception you may have of what a "token" is; for the purposes of the present devnet, "token" simply means "an indivisible unit which may be used for bonding onto the network".
