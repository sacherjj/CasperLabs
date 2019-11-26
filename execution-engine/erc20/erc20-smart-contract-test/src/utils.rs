use contract_ffi::key::Key;
use contract_ffi::value::Value;
use contract_ffi::value::U512;

use engine_tests::support::test_support::InMemoryWasmTestBuilder as TestBuilder;
use engine_tests::support::test_support::ExecuteRequestBuilder;

const ERC_20_CONTRACT_WASM: &str = "erc20_smart_contract.wasm";
const TRANFER_TO_ACCOUNT_WASM: &str = "transfer_to_account.wasm";

pub fn contract_hash(builder: &mut TestBuilder, account: [u8; 32], name: &str) -> [u8; 32] {
    let account_key = Key::Account(account);
    let value: Value = builder.query(None, account_key, &[name]).unwrap();
    if let Value::Key(Key::Hash(contract_hash)) = value {
        contract_hash.clone()
    } else {
        panic!("Can't extract contract hash.");
    }
}

pub fn deploy_erc20(builder: &mut TestBuilder, sender: [u8; 32], token_name: &str, init_balance: U512) -> ([u8; 32], [u8; 32]) {
    let request = ExecuteRequestBuilder::standard(
        sender, ERC_20_CONTRACT_WASM, ("deploy", token_name, init_balance)
    ).build();
    builder.exec(request).expect_success().commit();
    let token_hash = contract_hash(builder, sender, token_name);
    let proxy_hash = contract_hash(builder, sender, "erc20_proxy");
    (token_hash, proxy_hash)
}

pub fn assert_balance(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], address: [u8; 32], expected: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "assert_balance", address, expected)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn assert_total_supply(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], expected: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "assert_total_supply", expected)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn assert_allowance(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], owner: [u8; 32], spender: [u8; 32], expected: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "assert_allowance", owner, spender, expected)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn transfer(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], recipient: [u8; 32], amount: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "transfer", recipient, amount)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn transfer_from(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], owner: [u8; 32], recipient: [u8; 32], amount: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "transfer_from", owner, recipient, amount)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn approve(builder: &mut TestBuilder, proxy_hash: [u8; 32], token_hash: [u8; 32], 
    sender: [u8; 32], spender: [u8; 32], amount: U512) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender, proxy_hash, (token_hash, "approve", spender, amount)
    ).build();
    builder.exec(request).expect_success().commit();
}

pub fn transfer_motes(builder: &mut TestBuilder, sender: [u8; 32], recipient: [u8; 32], amount: u64) {
    let request = ExecuteRequestBuilder::standard(
        sender, TRANFER_TO_ACCOUNT_WASM, (recipient, amount)
    ).build();
    builder.exec(request).expect_success().commit();
}
