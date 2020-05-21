#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ContractHash, URef};

pub const MODIFIED_MINT_EXT_FUNCTION_NAME: &str = "modified_mint_ext";
pub const POS_EXT_FUNCTION_NAME: &str = "pos_ext";
pub const STANDARD_PAYMENT_FUNCTION_NAME: &str = "pay";

#[no_mangle]
pub extern "C" fn modified_mint_ext() {
    todo!("reimplement modified mint")
    // modified_mint::delegate();
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    unimplemented!();
    // pos::delegate();
}

#[no_mangle]
pub extern "C" fn pay() {
    standard_payment::delegate();
}

fn upgrade(_name: &str, _contract_hash: ContractHash) {
    // TODO use new upgrade functionality
    unimplemented!();
    //    runtime::upgrade_contract_at_uref(name, _contract_hash);
}

fn upgrade_mint() {
    const HASH_KEY_NAME: &str = "mint_hash";
    const ACCESS_KEY_NAME: &str = "mint_access";
    runtime::print(&format!(
        "upgrade mint keys: {:?}",
        runtime::list_named_keys()
    ));
    let _mint_ref = system::get_mint();
    let _mint_package_hash: ContractHash = runtime::get_key(HASH_KEY_NAME)
        .expect("should have mint")
        .into_hash()
        .expect("should be hash");
    let _mint_access_key: URef = runtime::get_key(ACCESS_KEY_NAME)
        .unwrap_or_revert()
        .into_uref()
        .expect("shuold be uref");
    todo!("reimplment");
    // upgrade(MODIFIED_MINT_EXT_FUNCTION_NAME, mint_ref);
}

#[allow(dead_code)]
fn upgrade_proof_of_stake() {
    let pos_ref = system::get_proof_of_stake();
    upgrade(POS_EXT_FUNCTION_NAME, pos_ref);
}

#[allow(dead_code)]
fn upgrade_standard_payment() {
    let standard_payment_ref = system::get_standard_payment();
    upgrade(STANDARD_PAYMENT_FUNCTION_NAME, standard_payment_ref);
}

#[no_mangle]
pub extern "C" fn call() {
    upgrade_mint();
    // upgrade_proof_of_stake();
    // upgrade_standard_payment();
}
