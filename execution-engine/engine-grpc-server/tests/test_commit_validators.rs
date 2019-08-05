extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_return_bonded_validators() {
    let genesis_validators: HashMap<PublicKey, U512> = vec![
        (PublicKey::new([1u8; 32]), U512::from(1000)),
        (PublicKey::new([2u8; 32]), U512::from(200)),
    ]
    .into_iter()
    .collect();

    let bonded_validators = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, genesis_validators.clone())
        .exec(GENESIS_ADDR, "local_state.wasm", DEFAULT_BLOCK_TIME, 1)
        .commit()
        .get_bonded_validators();

    assert_eq!(bonded_validators.len(), 2); // one for `run_genesis` and one for `exec + commti`.

    assert_eq!(bonded_validators[0], genesis_validators);
    assert_eq!(bonded_validators[1], genesis_validators);
}
