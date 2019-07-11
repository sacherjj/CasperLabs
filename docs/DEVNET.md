# DEVNET Quick Start Guide

This guide gives the simplest set of instructions for getting started on the CasperLabs devnet. More advanced users may wish to take other approaches to some of the steps listed here, however we do not go in to details of where these alternate approaches are possible.

## Creating an account

In order to interact with the devnet, you must have an account. CasperLabs has provided a convenient web app for creating an account and funding it with tokens. Note that the word "token" is used for convenience throughout this guide, however the units they correspond to have no monetary value whatsoever and do not necessarily correspond to the conception you may have of what a "token" is; for the purposes of the present devnet, "token" simply means "an indivisible unit which may be used for staking the network".

Creating an account using the UI proceeds as follows:
* Go to [explorer.casperlabs.io](https://explorer.casperlabs.io/)
* Click "Sign-in" in the top-right corner
* Follow the sign-up process
* Click the "Accounts" button in the left panel
* Click "Create Account" on the bottom left, enter a name for your new account, then click "save" in the pop-up box (you may need to grant access to allow multiple file downloads)
* Remember where you save the downloaded files to; you will need them later for deploying contracts!

The above process creates a public/private key pair which you will use to interact with the devnet. So far the on-chain account has not been created, we only have our keys saved to disk off-chain. To make the on-chain account we need to use the faucet, which will create the account by transferring tokens to the address which corresponds to our new public key.

Funding an account using the faucet:
* On [explorer.casperlabs.io](https://explorer.casperlabs.io/) click the "Faucet" button in the left panel
* Select the account created in the previous steps, then click "Request tokens"

You will see a new row populated in the "Request Status" table which includes a "Deploy Hash". You will also notice that the automatic monitoring of this deploy is currently under construction, so instead we will need to check the status of it manually. To do this you will need to have the CasperLabs client. Instructions for obtaining and running the client can be found in our [README](README.md). Once the client is installed then you should be able to run a command like
```
casperlabs-client --host deploy.casperlabs.io --port 40401 show-deploy <deploy-hash>
```
where `<deploy-hash>` comes from the "Request Status" table (you must include the complete hash, partial matching is not done), and receive an output like the following (though with different values in the various fields):
```
deploy {
  deploy_hash: "9671278921df52f21196ce30ea6c30d05176b8ea133beda27d4d22270ecc2c05"
  header {
    account_public_key: "1c73769f2ea7174432d83b284708b76578adce9eeacb9bf7dc069a17993453f3"
    nonce: 1
    timestamp: 1562678904560
    gas_price: 0
    body_hash: "6f993bd846ca603e72cfbb9f268616179ccea29af028072184687dbe40bda8d3"
  }
  approvals {
    approver_public_key: "1c73769f2ea7174432d83b284708b76578adce9eeacb9bf7dc069a17993453f3"
    signature {
      sig_algorithm: "ed25519"
      sig: "eecfeafeb1f1e4c08da1f98e9920cbe0a06fcaa2c95269e13f95945170508ab8a12539a7aaa3b9c854ecda73b3d1e199016360d60d2a6afc231284217f2d84029671278921df52f21196ce30ea6c30d05176b8ea133beda27d4d22270ecc2c05"
    }
  }
}
processing_results {
  block_info {
    summary {
      block_hash: "ef6d4c66a29d833de462fbb7fd35227cbc3849b36872940c852727f668d6993f"
      header {
        parent_hashes: "f4f39ad1bf75e7d5f78d6a2a3981a35e7c56702793d55f829e741968a5bfee64"
        justifications {
          validator_public_key: "1f66ea6321a48a935f66e97d4f7e60ee2d7fc9ccc62dfbe310f33b4839fc62eb"
          latest_block_hash: "f4f39ad1bf75e7d5f78d6a2a3981a35e7c56702793d55f829e741968a5bfee64"
        }
        justifications {
          validator_public_key: "569b41d574c46390212d698660b5326269ddb0a761d1294258897ac717b4958b"
          latest_block_hash: "66d38c4f0bacc61ddb9dabf0817604ebbf17f6ef16315c9ae3895100c1d0a45c"
        }
        justifications {
          validator_public_key: "89e744783c2d70902a5f2ef78e82e1f44102b5eb08ca6234241d95e50f615a6b"
          latest_block_hash: "66d38c4f0bacc61ddb9dabf0817604ebbf17f6ef16315c9ae3895100c1d0a45c"
        }
        justifications {
          validator_public_key: "d286526663ca3766c80781543a148c635f2388bfe128981c3e4ac69cea88dc35"
          latest_block_hash: "66d38c4f0bacc61ddb9dabf0817604ebbf17f6ef16315c9ae3895100c1d0a45c"
        }
        state {
          pre_state_hash: "b97285d04c32848b0dca6bd46581e999838e114d13618b54063b2e22c9f16284"
          post_state_hash: "fa926f7f6331a4f1d0718a5f18dbee442cb37abfa6626fad06b5a05f9356c28c"
          bonds {
            validator_public_key: "89e744783c2d70902a5f2ef78e82e1f44102b5eb08ca6234241d95e50f615a6b"
            stake: 5000000000
          }
          bonds {
            validator_public_key: "1f66ea6321a48a935f66e97d4f7e60ee2d7fc9ccc62dfbe310f33b4839fc62eb"
            stake: 8000000000
          }
          bonds {
            validator_public_key: "569b41d574c46390212d698660b5326269ddb0a761d1294258897ac717b4958b"
            stake: 4000000000
          }
          bonds {
            validator_public_key: "d286526663ca3766c80781543a148c635f2388bfe128981c3e4ac69cea88dc35"
            stake: 3000000000
          }
        }
        body_hash: "7a97d3dff84f13bf8ca08e04b86ca813a89e5564409d254aee274d098b43702a"
        timestamp: 1562678909860
        protocol_version: 1
        deploy_count: 1
        chain_id: "casperlabs"
        validator_block_seq_num: 2
        validator_public_key: "1f66ea6321a48a935f66e97d4f7e60ee2d7fc9ccc62dfbe310f33b4839fc62eb"
        rank: 2
      }
      signature {
        sig_algorithm: "ed25519"
        sig: "dd8183040dbedfc3cdbea088513887176141d7d2924c392830d7a93a73e7309e7b8090f2a4e324bad4fd3043f1964390a1925bb95c675810aba167b70472e90b"
      }
    }
    status {
      fault_tolerance: 0.0
      stats {
        block_size_bytes: 1098985
        deploy_error_count: 0
      }
    }
  }
  cost: 39383
  is_error: false
  error_message: ""
}
```
Note that if you you do not see that your deploy has been included in a block, or you see an error message that the deploy could not be found, then wait a minute and try again. Once you get a successful response as in the example above then you have created an on-chain account!

## Deploying code to the network

If you are interested in developing applications on the CasperLabs platform, read our [contracts documentation](CONTRACTS.md). If you wish to deploy your code to the devnet, keep the following things in mind:
* You must already have an account on the devnet (see instructions in the previous section)
* The `--host` argument to the CasperLabs client will be `deploy.casperlabs.io`
* You will need to keep track of the nonce for your devnet account, ensure you increment by 1 each time you deploy (beginning from 1 on the first deploy)
The final command will look something like
```
./client/bin/casperlabs-client \
        --host deploy.casperlabs.io \
        deploy \
        --nonce 1 \
        --session my_contract.wasm \
        --private-key account.private.key
```
Replace the `--session` arguments with the path to your compiled wasm (note these two arguments must be the same); replace the `--private-key` argument with the path to the private key file you downloaded from [explorer.casperlabs.io](https://explorer.casperlabs.io/) when creating your account.

## Bonding onto the network

You do not need to bond onto the network if all you want to do is deploy code. Bonding means that you will start a node which is able to make new block in the blockdag. In the final production system it will mean that you are a validator and your node will in part be responsible for the security of the network, however presently on the devnet it is mostly an opportunity for you to test-drive being a validator without much risk.

Bonding is done via the CasperLabs client (see the [README](../README.md) for instructions on installing and running it). Under the hood, this command is submitting a deploy to the network, so as in the previous section you must already have created an account. The command will look something like
```
./client/bin/casperlabs-client \
        --host deploy.casperlabs.io \
        bond \
        --amount 1000 \
        --nonce 1 \
        --private-key account.private.key
```
where you can fill in you own value for `--amount` (the amount you wish to bond), `--nonce` (more information on this shortly) and `--private-key` (the path to the private key you downloaded from [explorer.casperlabs.io](https://explorer.casperlabs.io/) during account creation). The `--nonce` argument must be sequential for all deploys on the account. This means that if you have previously submitting a bonding request, or some deployed some other contract, then you will need to give a nonce greater than 1 (depending on how many deploys you have done). The private key determines which account is being bonded and the public key for that account becomes your validator ID.

Once you have bonded you can run a node and use it to accept deploys and propose blocks. General instructions for running a node can be found in our [node documentation](NODE.md). Some additional notes for connecting to the devnet:
* To join the devnet P2P network start your node with a `--bootstrap` argument equal to one of the following:
  ```
  casperlabs://a605c8ddc4ed3dc9b881bfe006cc8175fb31e125@100.24.117.48?protocol=40400&discovery=40404

  casperlabs://c11559ba7f9159ee0fd184db50a1e3cac45ed191@52.15.234.164?protocol=40400&discovery=40404

  casperlabs://037cb8405f85d9f2d77c327173e8b973e949f07f@54.67.41.194?protocol=40400&discovery=40404

  casperlabs://feed990a543f0ac78757915ccebd63ca56c2288c@18.237.130.85?protocol=40400&discovery=40404
  ```
* Set the `--casper-validator-private-key-path` argument is set to be the same private key file you used for bonding above
* Ensure that the `--server-use-gossiping` flag is given
