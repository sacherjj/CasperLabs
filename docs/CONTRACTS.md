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

##### Step 1: Clone the [main repo](https://github.com/CasperLabs/CasperLabs/) to obtain the [example contracts](https://github.com/CasperLabs/CasperLabs/tree/dev/execution-engine/contracts/examples) and set up your toolchain
```
git clone git@github.com:CasperLabs/CasperLabs.git
cd CasperLabs/execution-engine
rustup toolchain install $(cat rust-toolchain)
rustup target add --toolchain $(cat rust-toolchain) wasm32-unknown-unknown
```

Source code of contract examples are currently located in `./execution-engine/contracts/examples` directory inside the main repo.

##### Step 2: Build the example contracts
```
make build-example-contracts
export COUNTER_DEFINE="$(pwd)/target/wasm32-unknown-unknown/release/counter_define.wasm"
export COUNTER_CALL="$(pwd)/target/wasm32-unknown-unknown/release/counter_call.wasm"
```

##### Step 3: Create an account at [clarity.casperlabs.io](https://clarity.casperlabs.io)

Create an account, which automatically creates a new keypair.  This keypair should be downloaded to the machine where you will deploy contracts.

##### Step 4: Add coins to this account

Add coins to this account using the [faucet](https://clarity.casperlabs.io/#/faucet).

##### Step 5: Deploy `counterdefine.wasm`

Note: `--payment-amount` is used to define the maximum number of motes to spend on the execution of the deploy. 
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_DEFINE
    --payment-amount <int>
```

You should see the following output:
```
Success!
```

##### Step 6: Observe

See the instructions [here](QUERYING.md).


##### Step 7: Deploy `countercall.wasm`

Note: `--payment-amount` is used to define the maximum number of motes to spend on the execution of the deploy.
```
casperlabs-client \
    --host deploy.casperlabs.io \
    deploy \
    --private-key <path-to-private-key> \
    --session $COUNTER_CALL
    --payment-amount <int>
```

You should see the following output:
```
Success!
```

###### Alternative way of creating, signing and deploying contracts

Every account can associate multiple keys with it and give each a weight. Collective weight of signing keys decides whether an action of certain type can be made. In order to collect weight of different associated keys a deploy has to be signed by corresponding private keys. `deploy` command does it all (creates a deploy, signs it and deploys to the node) but doesn't allow for signing with multiple keys. Therefore we split `deploy` into three separate commands:
* `make-deploy`  - creates a deploy from input parameters
* `sign-deploy`  - signs a deploy with given private key
* `print-deploy` - prints information of a deploy
* `send-deploy`  - sends a deploy to CasperLabs node

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

**Printing a deploy**
```
casperlabs-client \
    --host localhost \
    print-deploy
```
This will print information of a deploy into STDOUT. There are `--json` and `--bytes-standard` flags for, respectively, using standard JSON vs Protobuf text encoding and standard ASCII-escaped for Protobuf or Base64 for JSON bytes encoding vs custom Base16. The same set of flags also available for all `show-*` and `query-state` commands. 

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
which is a 256 bits long Blake2b hash of the deploy hash
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
associated with a name in the context of a user's account.

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

Note: names are valid only in the context of the account which called `add_uref`.

**Understanding difference between calling a contract directly and with `call_contract`**

When a contract is stored with `store_function` 
there is a new context created for it,
with initial content defined by the map passed to `store_function` as its second argument.
Later, when the stored contract is called with `call_contract` it is executed in this context.

In contrast, when the same stored contract is called directly,
for example, its address is passed to `--session-hash` argument of the `deploy` command,
the contract will be executed in the context of the account that creates the deploy.
The consequence of this is that stateful contracts designed to operate in a specific context
may not work as expected when called directly. 
They may, for instance, attempt to read or modify a `URef` that they expect to exist in their context,
but find it missing in the context that they are actually run in, that is of the deployer's account.

**Passing arguments to contracts**

Smart contracts can be parametrized.
A list of contract arguments can be specified
on command line when the contract is deployed.

When the contract code is executed
it can access individual arguments
by calling Contract API function `get_arg`
with index of an argument.
First argument is indexed with `0`.


**Command line client's syntax of contract arguments**

Client's `deploy` command accepts parameter `--session-args`
that can be used to specify types and values of contract arguments 
as a serialized sequence of
[Arg](https://github.com/CasperLabs/CasperLabs/blob/ca35f324179c93f0687ed4cf67d887176525b73b/protobuf/io/casperlabs/casper/consensus/consensus.proto#L78)
values
in a [protobuf JSON format](https://developers.google.com/protocol-buffers/docs/proto3#json),
with binary data represented in Base16 format.

For example: `--session-args '[{"name": "amount", "value": {"long_value": 123456}}]'`.


Note,
contract arguments are positional,
and so the `"name"` attribute is currently not used.
However, we plan to change contract arguments
to be keyword (named) arguments.
The structure of the `Arg` protobuf message
and its JSON serialized form is ready for this change.

In a future release
Contract API `get_arg` function
will change
to accept a string with a name of an argument
instead of it's index.

**Accessing arguments in contracts**

Contract API function `get_arg` allows to access contract arguments,
for example: 

```
let amount: u64 = get_arg(0);
```

will deserialize first contract argument as a value of type `u64`.
Note, types of the arguments specified when deploying
and the types in the Rust code must match.
The matching type for protobuf 
[Arg](https://github.com/CasperLabs/CasperLabs/blob/ca35f324179c93f0687ed4cf67d887176525b73b/protobuf/io/casperlabs/casper/consensus/consensus.proto#L78)
type `long_value`
is currently `u64`.

The same can be achieved by declaring return type of `get_arg` explicitly,
for example:

```
let amount = get_arg::<u64>(0);
```


**Supported types of contract arguments**


| protobuf [Arg](https://github.com/CasperLabs/CasperLabs/blob/ca35f324179c93f0687ed4cf67d887176525b73b/protobuf/io/casperlabs/casper/consensus/consensus.proto#L78) | Contract API type | Example value in [protobuf JSON format](https://developers.google.com/protocol-buffers/docs/proto3#json)
| ---------------  | ------------- | -------------------------------------
| `int_value`      | `u32`         | `'[{"name": "amount", "value": {"int_value": 123456}}]'`
| `long_value`     | `u64`         | `'[{"name": "amount", "value": {"long_value": 123456}}]'`
| `big_int`        | `u512`        | `'[{"name": "amount", "value": {"big_int": {"value": "123456", "bit_width": 512}}}]'`
| `string_value`   | `String`      | `'[{"name": "surname", "value": {"string_value": "Nakamoto"}}]'`
| `optional_value` | `Option<T>`   | `'{"name": "maybe_number", "value": {"optional_value": {}}}` or  `{"name": "maybe_number", "value": {"optional_value": {"long_value": 1000000}}}'`
| `hash`           | `Key::Hash`    | `'{"name": "my_hash", "value": {"key": {"hash": {"hash": "9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414"}}}}'`
| `address`        | `Key::Address` | `'{"name": "my_address", "value": {"key": {"address": {"account": "9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414"}}}}'`
| `uref`           | `Key::URef`    | `'{"name": "my_uref", "value": {"key": {"uref": {"uref": "9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414", "access_rights": 5}}}}'`
| `local`          | `Key::Local`   | `'{"name": "my_local", "value": {"key": {"local": {"hash": "9d39b7fba47d07c1af6f711efe604a112ab371e2deefb99a613d2b3dcdfba414"}}}}'`
| `int_list`       | `Vec<i32>`         | `'{"name": "my_int_list", "value": {"int_list": {"values": [0, 1, 2]}}}'`
| `string_list`    | `Vec<String>`         | `'{"name": "my_string_list", "value": {"string_list": {"values": ["A", "B", "C"]}}}'`

Numeric values of `access_rights` in `uref` are defined in
[`enum AccessRights in state.proto](https://github.com/CasperLabs/CasperLabs/blob/ca35f324179c93f0687ed4cf67d887176525b73b/protobuf/io/casperlabs/casper/consensus/state.proto#L58).


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
