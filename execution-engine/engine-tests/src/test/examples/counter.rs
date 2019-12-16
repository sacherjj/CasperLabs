extern crate alloc;

use contract_ffi::{
    key::Key,
    value::{Value, U512},
};
use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder};
use crate::{
    test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG},
};

const TRANFER_TO_ACCOUNT_WASM: &str = "transfer_to_account.wasm";
const COUNTER_DEFINE_WASM: &str = "counter_define.wasm";

const ACCOUNT_1: [u8; 32] = DEFAULT_ACCOUNT_ADDR;
const ACCOUNT_2: [u8; 32] = [2u8; 32];

#[ignore]
#[test]
fn test_counter_direct_call() {
    // Init virtual machine.
    let mut builder = TestBuilder::default();
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
    
    // Make the ACCOUNT_2 balance positive, so it can pay for deploys.
    transfer_motes(&mut builder, ACCOUNT_1, ACCOUNT_2, 100_000_000);

    // Deploy counter as ACCOUNT_1
    let counter_hash = deploy_counter(&mut builder, ACCOUNT_1);

    // 
}

pub fn deploy_counter(
    builder: &mut TestBuilder,
    sender: [u8; 32]
) -> [u8; 32] {
    let request = ExecuteRequestBuilder::standard(
        sender, COUNTER_DEFINE_WASM, ()
    ).build();
    builder.exec(request).expect_success().commit();
    contract_hash(builder, sender, "counter")
}

pub fn transfer_motes(
    builder: &mut TestBuilder,
    sender: [u8; 32],
    recipient: [u8; 32],
    amount: u64,
) {
    let request =
        ExecuteRequestBuilder::standard(sender, TRANFER_TO_ACCOUNT_WASM, (recipient, amount))
            .build();
    builder.exec(request).expect_success().commit();
}

pub fn contract_hash(builder: &mut TestBuilder, account: [u8; 32], name: &str) -> [u8; 32] {
    let account_key = Key::Account(account);
    let value: Value = builder.query(None, account_key, &[name]).unwrap();
    if let Value::Key(Key::Hash(contract_hash)) = value {
        contract_hash
    } else {
        panic!("Can't extract contract hash.");
    }
}