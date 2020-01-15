use alloc::{collections::BTreeMap, string::String};

use crate::{
    api::{self, Api},
    error::Error,
};
use contract::contract_api::{runtime, storage, system, TURef};
use types::{ContractRef, Key, U512};

// ERC20 smart contract.
#[allow(unused_imports)]
use crate::erc20::{erc20, PURSE_NAME};

// Proxy smart contract.
#[allow(unused_imports)]
use crate::proxy::erc20_proxy;

const ERC20_CONTRACT_NAME: &str = "erc20";
const ERC20_PROXY_CONTRACT_NAME: &str = "erc20_proxy";

pub fn deploy() {
    match Api::from_args() {
        Api::Deploy(name, initial_balance) => {
            deploy_token(&name, initial_balance);
            deploy_proxy();
        }
        _ => runtime::revert(Error::UnknownDeployCommand),
    }
}

fn deploy_token(name: &str, initial_balance: U512) {
    // Create a smart contract purse.
    let token_purse = system::create_purse();
    let mut token_urefs: BTreeMap<String, Key> = BTreeMap::new();
    token_urefs.insert(String::from(PURSE_NAME), token_purse.value().into());

    // Create erc20 token instance.
    let token_ref: ContractRef = storage::store_function_at_hash(ERC20_CONTRACT_NAME, token_urefs);

    // Initialize erc20 contract.
    runtime::call_contract::<_, ()>(token_ref.clone(), (api::INIT_ERC20, initial_balance));

    // Save it under a new TURef.
    let token_turef: TURef<Key> = storage::new_turef(token_ref.into());

    // Save TURef under readable name.
    runtime::put_key(&name, token_turef.into());
}

fn deploy_proxy() {
    // Create proxy instance.
    let proxy_ref: ContractRef =
        storage::store_function_at_hash(ERC20_PROXY_CONTRACT_NAME, Default::default());

    // Save it under a new TURef.
    let proxy_turef: TURef<Key> = storage::new_turef(proxy_ref.into());

    // Save TURef under readable name.
    runtime::put_key(ERC20_PROXY_CONTRACT_NAME, proxy_turef.into());
}
