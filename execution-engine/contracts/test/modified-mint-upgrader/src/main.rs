#![no_std]
#![no_main]

// use contract::contract_api::{runtime, system};
// use types::{ApiError, Key};

// #[repr(u16)]
// enum CustomError {
//     ContractPointerHash = 1,
//     UnexpectedKeyVariant = 2,
// }

// pub const EXT_FUNCTION_NAME: &str = "modified_mint_ext";

// #[no_mangle]
// pub extern "C" fn modified_mint_ext() {
//     modified_mint::delegate();
// }

#[no_mangle]
pub extern "C" fn call() {
    todo!("contracts are no longer stored under urefs");
    // let contract_hash = system::get_mint();
    //
    // runtime::upgrade_contract_at_uref(EXT_FUNCTION_NAME, contract_hash);
}
