use alloc::{collections::BTreeMap, string::String};

use crate::{
    api::{self, Api, VestingConfig},
    error::Error,
};
use contract::{
    contract_api::{account, runtime, storage, system, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ContractRef, Key};

// Vault smart contract.
#[allow(unused_imports)]
use crate::vesting::{vesting, PURSE_NAME};

// Proxy smart contract.
#[allow(unused_imports)]
use crate::proxy::vesting_proxy;

const VESTING_CONTRACT_NAME: &str = "vesting";
const VESTING_PROXY_CONTRACT_NAME: &str = "vesting_proxy";

pub fn deploy() {
    match Api::from_args() {
        Api::Deploy(name, admin, recipient, vesting_config) => {
            deploy_vesting_contract(&name, admin, recipient, vesting_config);
            deploy_proxy();
        }
        _ => runtime::revert(Error::UnknownDeployCommand),
    }
}

fn deploy_vesting_contract(
    name: &str,
    admin: PublicKey,
    recipient: PublicKey,
    vesting_config: VestingConfig,
) {
    // Create a smart contract purse.
    let main_purse = account::get_main_purse();
    let vesting_purse = system::create_purse();
    system::transfer_from_purse_to_purse(main_purse, vesting_purse, vesting_config.total_amount)
        .unwrap_or_revert_with(Error::PurseTransferError);
    let mut vesting_urefs: BTreeMap<String, Key> = BTreeMap::new();
    vesting_urefs.insert(String::from(PURSE_NAME), vesting_purse.value().into());

    // Create vesting instance.
    let vesting_ref: ContractRef =
        storage::store_function_at_hash(VESTING_CONTRACT_NAME, vesting_urefs);

    // Initialize vesting contract.
    runtime::call_contract::<_, ()>(
        vesting_ref.clone(),
        (
            api::INIT,
            admin,
            recipient,
            vesting_config.cliff_time,
            vesting_config.cliff_amount,
            vesting_config.drip_period,
            vesting_config.drip_amount,
            vesting_config.total_amount,
            vesting_config.admin_release_period,
        ),
    );

    // Save it under a new TURef.
    let vesting_turef: TURef<Key> = storage::new_turef(vesting_ref.into());

    // Save TURef under readable name.
    runtime::put_key(&name, vesting_turef.into());
}

fn deploy_proxy() {
    // Create proxy instance.
    let proxy_ref: ContractRef =
        storage::store_function_at_hash(VESTING_PROXY_CONTRACT_NAME, Default::default());

    // Save it under a new TURef.
    let proxy_turef: TURef<Key> = storage::new_turef(proxy_ref.into());

    // Save TURef under readable name.
    runtime::put_key(VESTING_PROXY_CONTRACT_NAME, proxy_turef.into());
}
