use crate::{
    support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder},
    test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG},
};

#[ignore]
#[test]
fn should_charge_gas_for_subcall() {
    const CONTRACT_NAME: &str = "measure_gas_subcall.wasm";
    const DO_NOTHING: &str = "do-nothing";
    const DO_SOMETHING: &str = "do-something";
    const NO_SUBCALL: &str = "no-subcall";

    let do_nothing_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_NAME, (DO_NOTHING,)).build();

    let do_something_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_NAME, (DO_SOMETHING,))
            .build();

    let no_subcall_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_NAME, (NO_SUBCALL,)).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(do_nothing_request)
        .expect_success()
        .commit()
        .exec(do_something_request)
        .expect_success()
        .commit()
        .exec(no_subcall_request)
        .expect_success()
        .commit()
        .finish();

    let do_nothing_cost = builder.exec_costs(0)[0];

    let do_something_cost = builder.exec_costs(1)[0];

    let no_subcall_cost = builder.exec_costs(2)[0];

    assert_ne!(
        do_nothing_cost, do_something_cost,
        "should have different costs"
    );

    assert_ne!(
        no_subcall_cost, do_something_cost,
        "should have different costs"
    );

    assert!(
        do_nothing_cost < do_something_cost,
        "should cost more to do something via subcall"
    );

    assert!(
        no_subcall_cost < do_nothing_cost,
        "do nothing in a subcall should cost more than no subcall"
    );
}
