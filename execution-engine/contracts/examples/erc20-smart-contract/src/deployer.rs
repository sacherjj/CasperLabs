use alloc::vec;

use contract_ffi::{
    contract_api::{runtime, storage, ContractRef, TURef},
    key::Key,
    value::U512,
};

use crate::{
    api::{self, Api},
    error::Error,
};

// ERC20 smart contract.
#[allow(unused_imports)]
use crate::erc20::erc20;

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
    // Create erc20 token instance.
    let token_ref: ContractRef =
        storage::store_function_at_hash(ERC20_CONTRACT_NAME, Default::default());

    // Initialize erc20 contract.
    runtime::call_contract::<_, ()>(
        token_ref.clone(),
        (api::INIT_ERC20, initial_balance),
        vec![],
    );

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
