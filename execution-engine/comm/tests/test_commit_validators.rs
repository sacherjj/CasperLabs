extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::value::account::PublicKey;
use common::value::U512;
use test_support::WasmTestBuilder;

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
        .exec(GENESIS_ADDR, "local_state.wasm", 1, vec![])
        .commit()
        .get_bonded_validators();

    assert_eq!(bonded_validators.len(), 2); // one for `run_genesis` and one for `exec + commti`.

    assert_eq!(bonded_validators[0], genesis_validators);
    assert_eq!(bonded_validators[1], genesis_validators);
}
