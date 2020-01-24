use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};
use types::key::Key;

const MAILING_CALL: &str = "mailing_list_call.wasm";
const MAILING_DEFINE: &str = "mailing_list_define.wasm";
const LIST_KEY: &str = "list";
const MAILING_KEY: &str = "mailing";
const MAIL_FEED_KEY: &str = "mail_feed";

#[ignore]
#[test]
fn should_run_mailing_example_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, MAILING_DEFINE, ()).build();

    let exec_request_2 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, MAILING_CALL, ()).build();

    let exec_request_3 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, MAILING_CALL, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    builder.exec(exec_request_2).expect_success().commit();

    builder.exec(exec_request_3).expect_success().commit();

    let account_value = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account");
    let account = account_value.as_account().expect("should be account");

    // Mail feed

    let mail_feed_key = account
        .named_keys()
        .get(MAIL_FEED_KEY)
        .expect("should have counter key");
    let stored_value = builder
        .query(None, *mail_feed_key, &[])
        .expect("should have counter value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: Vec<String> = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(value, vec!["Hello again!".to_string()]);

    // Mailing key
    let mailing_key = account
        .named_keys()
        .get(MAILING_KEY)
        .expect("should have counter key");
    let stored_value = builder
        .query(None, *mailing_key, &[LIST_KEY])
        .expect("should have counter value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: Vec<String> = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(value, vec!["CasperLabs".to_string()]);

    // List key

    let stored_value = builder
        .query(None, *mailing_key, &["CasperLabs"])
        .expect("should have list value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: Vec<String> = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(
        value,
        vec![
            "Welcome!".to_string(),
            "Hello, World!".to_string(),
            "Hello, World!".to_string()
        ]
    );
}
