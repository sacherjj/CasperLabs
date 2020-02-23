use alloc::{collections::BTreeMap, string::String};

use crate::{
    api::{self, Api},
    error::Error,
};
use contract::contract_api::{runtime, storage, system};
use types::{ContractRef, Key, U512};

use crate::erc20::PURSE_NAME;

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
    token_urefs.insert(String::from(PURSE_NAME), token_purse.into());

    // Create erc20 token instance.
    let token_ref: ContractRef = storage::store_function_at_hash(ERC20_CONTRACT_NAME, token_urefs);

    // Initialize erc20 contract.
    runtime::call_contract::<_, ()>(token_ref.clone(), (api::INIT_ERC20, initial_balance));

    let contract_key: Key = token_ref.into();

    // Save it under a new URef.
    let token: Key = storage::new_uref(contract_key).into();

    // Save URef under readable name.
    runtime::put_key(&name, token);
}

fn deploy_proxy() {
    // Create proxy instance.
    let proxy_ref: ContractRef =
        storage::store_function_at_hash(ERC20_PROXY_CONTRACT_NAME, Default::default());

    let contract_key: Key = proxy_ref.into();

    // Save it under a new URef.
    let proxy: Key = storage::new_uref(contract_key).into();

    // Save URef under readable name.
    runtime::put_key(ERC20_PROXY_CONTRACT_NAME, proxy);
}
