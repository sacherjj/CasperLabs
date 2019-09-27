use contract_ffi::contract_api::argsparser::ArgsParser;
use contract_ffi::contract_api::Error;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    self, InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[derive(Debug)]
#[repr(u16)]
enum GetArgContractError {
    MissingArgument0 = 0,
    MissingArgument1,
    InvalidArgument0,
    InvalidArgument1,
}

const ARG0_VALUE: &str = "Hello, world!";
const ARG1_VALUE: u64 = 42;

/// Calls get_arg contract and returns Ok(()) in case no error, or String which is the error message
/// returned by the engine
fn call_get_arg(args: impl ArgsParser) -> Result<(), String> {
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "get_arg.wasm",
            args,
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .commit()
        .finish();

    if !result.builder().is_error() {
        return Ok(());
    }

    let response = result
        .builder()
        .get_exec_response(0)
        .expect("should have a response")
        .to_owned();

    let error_message = {
        let execution_result = test_support::get_success_result(&response);
        test_support::get_error_message(execution_result)
    };

    Err(error_message)
}

#[ignore]
#[test]
fn should_use_passed_argument() {
    call_get_arg((String::from(ARG0_VALUE), U512::from(ARG1_VALUE)))
        .expect("Should successfuly call get_arg with 2 valid args");
}

#[ignore]
#[test]
fn should_revert_with_missing_arg() {
    assert_eq!(
        call_get_arg(()).expect_err("should fail"),
        format!(
            "Exit code: {}",
            u32::from(Error::User(GetArgContractError::MissingArgument0 as u16))
        )
    );
    assert_eq!(
        call_get_arg((String::from(ARG0_VALUE),)).expect_err("should fail"),
        format!(
            "Exit code: {}",
            u32::from(Error::User(GetArgContractError::MissingArgument1 as u16))
        )
    );
}

#[ignore]
#[test]
fn should_revert_with_invalid_argument() {
    assert_eq!(
        call_get_arg((U512::from(123),)).expect_err("should fail"),
        format!(
            "Exit code: {}",
            u32::from(Error::User(GetArgContractError::InvalidArgument0 as u16))
        )
    );
    assert_eq!(
        call_get_arg((
            String::from(ARG0_VALUE),
            String::from("this is expected to be U512")
        ))
        .expect_err("should fail"),
        format!(
            "Exit code: {}",
            u32::from(Error::User(GetArgContractError::InvalidArgument1 as u16))
        )
    );
}
