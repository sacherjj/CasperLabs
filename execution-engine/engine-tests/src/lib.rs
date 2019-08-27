#[cfg(test)]
extern crate grpc;
#[macro_use]
#[cfg(test)]
extern crate lazy_static;
#[cfg(test)]
extern crate protobuf;

#[cfg(test)]
extern crate contract_ffi;
#[cfg(test)]
extern crate engine_core;
#[cfg(test)]
extern crate engine_grpc_server;
#[cfg(test)]
extern crate engine_shared;
#[cfg(test)]
extern crate engine_storage;
#[cfg(test)]
extern crate engine_wasm_prep;

#[cfg(test)]
pub mod test_stored_contract_support;
#[cfg(test)]
pub mod test_support;

#[cfg(test)]
mod regression_test_ee_221;
#[cfg(test)]
mod regression_test_ee_401;
#[cfg(test)]
mod regression_test_ee_441;
#[cfg(test)]
mod regression_test_ee_460;
#[cfg(test)]
mod regression_test_ee_468;
#[cfg(test)]
mod regression_test_ee_532;
#[cfg(test)]
mod regression_test_ee_536;
#[cfg(test)]
mod regression_test_ee_539;
#[cfg(test)]
mod regression_test_ee_549;
#[cfg(test)]
mod regression_test_ee_572;
#[cfg(test)]
mod regression_test_node_635;
#[cfg(test)]
mod test_associated_keys;
#[cfg(test)]
mod test_authorized_keys;
#[cfg(test)]
mod test_commit_validators;
#[cfg(test)]
mod test_genesis;
#[cfg(test)]
mod test_genesis_hash_match;
#[cfg(test)]
mod test_get_blocktime;
#[cfg(test)]
mod test_get_caller;
#[cfg(test)]
mod test_get_phase;
#[cfg(test)]
mod test_key_management_thresholds;
#[cfg(test)]
mod test_known_urefs;
#[cfg(test)]
mod test_local_state;
#[cfg(test)]
mod test_main_purse;
#[cfg(test)]
mod test_metrics;
#[cfg(test)]
mod test_payment_code;
#[cfg(test)]
mod test_pos_bonding;
#[cfg(test)]
mod test_pos_finalize_payment;
#[cfg(test)]
mod test_pos_get_payment_purse;
#[cfg(test)]
mod test_pos_refund_purse;
#[cfg(test)]
mod test_preconditions;
#[cfg(test)]
mod test_revert;
#[cfg(test)]
mod test_stored_contract_exec;
#[cfg(test)]
mod test_system_contract_urefs_access_rights;
#[cfg(test)]
mod test_transfer;
#[cfg(test)]
mod test_transfer_purse_to_account;
#[cfg(test)]
mod test_transfer_purse_to_purse;
