use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, runtime_args, RuntimeArgs, U512};

const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([1u8; 32]);
const ARG_PAYMENT_AMOUNT: &str = "payment_amount";

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_default_account() {
    let mut builder = initialize();
    refund_tests(&mut builder, DEFAULT_ACCOUNT_ADDR);
}

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_account_1() {
    let mut builder = initialize();
    transfer(&mut builder, ACCOUNT_1_ADDR, *DEFAULT_PAYMENT * 2);
    refund_tests(&mut builder, ACCOUNT_1_ADDR);
}

fn initialize() -> InMemoryWasmTestBuilder {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder
}

fn transfer(builder: &mut InMemoryWasmTestBuilder, public_key: PublicKey, amount: U512) {
    let exec_request = {
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
            runtime_args! {
                "target" => public_key,
                "amount" => amount,
            },
        )
        .build()
    };

    builder.exec(exec_request).expect_success().commit();
}

fn refund_tests(builder: &mut InMemoryWasmTestBuilder, public_key: PublicKey) {
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(public_key)
            .with_deploy_hash([2; 32])
            .with_session_code("do_nothing.wasm", RuntimeArgs::default())
            .with_payment_code(
                "pos_refund_purse.wasm",
                runtime_args! { ARG_PAYMENT_AMOUNT => *DEFAULT_PAYMENT },
            )
            .with_authorization_keys(&[public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request).expect_success().commit();
}
