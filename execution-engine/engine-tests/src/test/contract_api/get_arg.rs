use contract::args_parser::ArgsParser;
use engine_test_support::low_level::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG,
};
use types::{ApiError, U512};

#[derive(Debug)]
#[repr(u16)]
enum GetArgContractError {
    MissingArgument0 = 0,
    MissingArgument1,
    InvalidArgument0,
    InvalidArgument1,
}

const CONTRACT_GET_ARG: &str = "get_arg.wasm";
const ARG0_VALUE: &str = "Hello, world!";
const ARG1_VALUE: u64 = 42;

/// Calls get_arg contract and returns Ok(()) in case no error, or String which is the error message
/// returned by the engine
fn call_get_arg(args: impl ArgsParser) -> Result<(), String> {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GET_ARG, args).build();
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    if !result.builder().is_error() {
        return Ok(());
    }

    let response = result
        .builder()
        .get_exec_response(0)
        .expect("should have a response");

    let error_message = utils::get_error_message(response);

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
    assert!(call_get_arg(())
        .expect_err("should fail")
        .contains(&format!(
            "Revert({})",
            u32::from(ApiError::User(GetArgContractError::MissingArgument0 as u16))
        )));
    assert!(call_get_arg((String::from(ARG0_VALUE),))
        .expect_err("should fail")
        .contains(&format!(
            "Revert({})",
            u32::from(ApiError::User(GetArgContractError::MissingArgument1 as u16))
        )));
}

#[ignore]
#[test]
fn should_revert_with_invalid_argument() {
    assert!(call_get_arg((U512::from(123),))
        .expect_err("should fail")
        .contains(&format!(
            "Revert({})",
            u32::from(ApiError::User(GetArgContractError::InvalidArgument0 as u16))
        )));
    assert!(call_get_arg((
        String::from(ARG0_VALUE),
        String::from("this is expected to be U512")
    ))
    .expect_err("should fail")
    .contains(&format!(
        "Revert({})",
        u32::from(ApiError::User(GetArgContractError::InvalidArgument1 as u16))
    )));
}
