use contract_ffi::{key::Key, value::U512};
use engine_shared::stored_value::StoredValue;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder};

const ERC_20_CONTRACT_WASM: &str = "erc20_smart_contract.wasm";
const TRANFER_TO_ACCOUNT_WASM: &str = "transfer_to_account.wasm";

const METHOD_DEPLOY: &str = "deploy";
const METHOD_ASSERT_BALLANCE: &str = "assert_balance";
const METHOD_ASSERT_TOTAL_SUPPLY: &str = "assert_total_supply";
const METHOD_ASSERT_ALLOWANCE: &str = "assert_allowance";
const METHOD_TRANSFER: &str = "transfer";
const METHOD_TRANSFER_FROM: &str = "transfer_from";
const METHOD_APPROVE: &str = "approve";

const UREF_NAME_ERC20_PROXY: &str = "erc20_proxy";

pub fn contract_hash(builder: &mut TestBuilder, account: [u8; 32], name: &str) -> [u8; 32] {
    let account_key = Key::Account(account);
    let stored_value = builder.query(None, account_key, &[name]).unwrap();
    if let StoredValue::CLValue(cl_value) = stored_value {
        let key: Key = cl_value.to_t().unwrap();
        if let Key::Hash(contract_hash) = key {
            contract_hash
        } else {
            panic!("Key isn't Hash type");
        }
    } else {
        panic!("Can't extract contract hash.");
    }
}

pub fn deploy_erc20(
    builder: &mut TestBuilder,
    sender: [u8; 32],
    token_name: &str,
    init_balance: U512,
) -> ([u8; 32], [u8; 32]) {
    let request = ExecuteRequestBuilder::standard(
        sender,
        ERC_20_CONTRACT_WASM,
        (METHOD_DEPLOY, token_name, init_balance),
    )
    .build();
    builder.exec(request).expect_success().commit();
    let token_hash = contract_hash(builder, sender, token_name);
    let proxy_hash = contract_hash(builder, sender, UREF_NAME_ERC20_PROXY);
    (token_hash, proxy_hash)
}

pub fn assert_balance(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    address: [u8; 32],
    expected: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (token_hash, METHOD_ASSERT_BALLANCE, address, expected),
    )
    .build();
    builder.exec(request).expect_success().commit();
}

pub fn assert_total_supply(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    expected: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (token_hash, METHOD_ASSERT_TOTAL_SUPPLY, expected),
    )
    .build();
    builder.exec(request).expect_success().commit();
}

pub fn assert_allowance(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    owner: [u8; 32],
    spender: [u8; 32],
    expected: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (
            token_hash,
            METHOD_ASSERT_ALLOWANCE,
            owner,
            spender,
            expected,
        ),
    )
    .build();
    builder.exec(request).expect_success().commit();
}

pub fn transfer(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    recipient: [u8; 32],
    amount: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (token_hash, METHOD_TRANSFER, recipient, amount),
    )
    .build();
    builder.exec(request).expect_success().commit();
}

pub fn transfer_from(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    owner: [u8; 32],
    recipient: [u8; 32],
    amount: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (token_hash, METHOD_TRANSFER_FROM, owner, recipient, amount),
    )
    .build();
    builder.exec(request).expect_success().commit();
}

pub fn approve(
    builder: &mut TestBuilder,
    proxy_hash: [u8; 32],
    token_hash: [u8; 32],
    sender: [u8; 32],
    spender: [u8; 32],
    amount: U512,
) {
    let request = ExecuteRequestBuilder::contract_call_by_hash(
        sender,
        proxy_hash,
        (token_hash, METHOD_APPROVE, spender, amount),
    )
    .build();
    builder.exec(request).expect_success().commit();
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
