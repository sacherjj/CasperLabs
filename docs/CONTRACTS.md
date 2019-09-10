# Deploying Contracts

## Prerequisites

#### Using binaries (recommended):
* Install [`rustup`](https://rustup.rs/).
* Install the [`casperlabs`](INSTALL.md) package, which contains `casperlabs-client`.

#### Building from source:
* Install [`rustup`](https://rustup.rs/).
* Build the [`casperlabs-client`](BUILD.md#build-the-client).

If you build from source, you will need to add the build directories to your `PATH`, for example:
```
export PATH="<path-to-CasperLabs-repo>/client/target/universal/stage/bin:$PATH"
```
Or you can run the client commands from the root directory of the repo, using explicit paths to the binaries.

## Instructions

##### Step 1: Clone the [examples](https://github.com/CasperLabs/contract-examples) and set up your toolchain
```
git clone git@github.com:CasperLabs/contract-examples.git
cd contract-examples
rustup toolchain install $(cat rust-toolchain)
rustup target add --toolchain $(cat rust-toolchain) wasm32-unknown-unknown
```

##### Step 2: Build the example contracts
```
cargo build --release
export COUNTER_DEFINE="$(pwd)/target/wasm32-unknown-unknown/release/counterdefine.wasm"
export COUNTER_CALL="$(pwd)/target/wasm32-unknown-unknown/release/countercall.wasm"
```

##### Step 3: Create an account at [explorer.casperlabs.io](https://explorer.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will deploy contracts.

##### Step 4: Add coins to this account

Add coins to this account using the [faucet](https://explorer.casperlabs.io/#/faucet).

##### Step 5: Deploy `counterdefine.wasm`
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_DEFINE
```

You should see the following output:
```
Success!
```

##### Step 6: Observe

See the instructions [here](QUERYING.md).


##### Step 7: Deploy `countercall.wasm`
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_CALL
```

You should see the following output:
```
Success!
```

###### Alternative way of creating, signing and deploying contracts

Every account can associate multiple keys with it and give each a weight. Collective weight of signing keys decides whether an action of certain type can be made. In order to collect weight of different associated keys a deploy has to be signed by corresponding private keys. `deploy` command does it all (creates a deploy, signs it and deploys to the node) but doesn't allow for signing with multiple keys. Therefore we split `deploy` into three separate commands:
* `make-deploy` - creates a deploy from input parameters
* `sign-deploy` - signs a deploy with given private key
* `send-deploy` - sends a deploy to CasperLabs node

Commands read input deploy from both a file (`-i` flag) and STDIN. They can also write to both file and STDOUT.

Example usage:

**Creating a deploy**
```
casperlabs-client \
    --host localhost \
    make-deploy \
    --session session-code.wasm \
    --payment payment-code.wasm \
    --from a1130120d27f6f692545858cc5c284b1ef30fe287caef648b0c405def88f543a
```
This will write a deploy in binary format to STDOUT. It's possible to write it to a file, by supplying `-o` argument:
```
casperlabs-client \
    --host localhost \
    make-deploy \
    --session session-code.wasm \
    --payment payment-code.wasm \
    --from a1130120d27f6f692545858cc5c284b1ef30fe287caef648b0c405def88f543a
    -o /deploys/deploy_1
```

**Signing a deploy**
```
casperlabs-client \
    --host localhost \
    sign-deploy \
    --public-key public-key.pem \
    --private-key private-key.pem
```
This will read a deploy to sign from STDIN and output signed deploy to STDOUT. There are `-i` and `-o` flags for, respectively, reading a deploy from a file and writing signed deploy to a file.

**Sending deploy to the node**
```
casperlabs-client \
    --host localhost \
    send-deploy
```
In the example above there is no `-i` argument, meaning that signed deploy will be read from STDIN.

Reading from STDIN and writing to STDOUT allows for piping output from one command to the input of another one (commands are incomplete for better readability):
```
casperlabs-client make-deploy [arguments] | \
casperlabs-client sign-deploy --private-key [private_key] --public-key [public_key] | \
casperlabs-client send-deploy
```

For more detailed description, use `--help` flag (`casper-client --help`).

##### Step 8: Observe

See the instructions [here](QUERYING.md).

###### Advanced deploy options

**Stored contracts**

A function that is part of the deployed contract's module
can be saved on the blockchain 
with Contract API function `store_function`.
Such function becomes a stored contract that
can be later called from another contract with `call_contract`
or used instead of a WASM file when creating a new deploy on command line.


**Contract address**

A contract stored on blockchain with `store_function` has an address,
which is a 32 bits long Blake2b hash of the deploy hash
and a 32 bits integer function counter.
The function counter is equal `0` for the first function saved
with `store_function` during execution of a deploy,
`1` for the second stored function, and so on.


**Calling a stored contract using its address**

Contract address is a cryptographic hash
uniquely identifyiyng a stored contract in the system.
Thus, it can be used to call the stored contract,
both directly when creating a deploy, e.g. on command line
or from another contract.

`casperlabs-client` `deploy` command accepts argument `--session-hash`
which can be used to create a deploy using a stored contract
instead of a file with a compiled WASM module.
Its value should be a base16 representation of the contract address,
for example: `--session-hash 2358448f76c8b3a9e263571007998791a815e954c3c3db2da830a294ea7cba65`.


`payment-hash` is an option equivalent to `--session-hash`
but for specifying address of payment contract.

**Calling a stored contract by name**

For convenience, a contract address can be 
associated with a name in the context of user's account.

Typically this is done in the same contract that calls `store_function`.
In the example below 
`counter_ext` is a function in the same module as the executing contract.
The function is stored on blockchain with `store_function`.
Next, a call to `add_uref` associates the stored contract's address with a name `"counter"`.

```
    //create map of references for stored contract
    let mut counter_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let pointer = store_function("counter_ext", counter_urefs);
    add_uref("counter", &pointer.into());

```

`casperlabs-client` `deploy` command accepts argument `--session-name`
which can be used to refer to a stored contract by its name,
for example `--session-name counter`.
This option can be used
to create a deploy with a stored contract
acting as the deploy's session contract.

Equivalent argument for payment contract is `--payment-name`.

Note, UREFs are valid only in the context of a specific account.


####  Using a local standalone node

If you are testing with a [local standalone node](NODE.md#running-a-single-node), you will need to change the `--host` argument:

```
casperlabs-client \
    --host 127.0.0.1 \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_DEFINE
```

You will also need to explicitly propose after making a deploy (or several deploys), in order for your deploys to be committed:

```
casperlabs-client --host 127.0.0.1 propose
```
