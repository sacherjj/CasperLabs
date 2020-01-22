//! This executable is designed to be run to set up global state in preparation for running other
//! standalone test executable(s).  This will allow profiling to be done on executables running only
//! meaningful code, rather than including test setup effort in the profile results.

use std::{env, path::PathBuf};

use base16;
use clap::{crate_version, App};

use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, LmdbWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};
use types::account::PublicKey;

use casperlabs_engine_tests::profiling;

const ABOUT: &str = "Initializes global state in preparation for profiling runs. Outputs the root \
                     hash from the commit response.";
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";

fn data_dir() -> PathBuf {
    let exe_name = profiling::exe_name();
    let data_dir_arg = profiling::data_dir_arg();
    let arg_matches = App::new(&exe_name)
        .version(crate_version!())
        .about(ABOUT)
        .arg(data_dir_arg)
        .get_matches();
    profiling::data_dir(&arg_matches)
}

fn main() {
    let data_dir = data_dir();

    let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);
    let account_1_public_key = profiling::account_1_public_key();
    let account_1_initial_amount = profiling::account_1_initial_amount();
    let account_2_public_key = profiling::account_2_public_key();

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "state_initializer.wasm",
                (
                    account_1_public_key,
                    account_1_initial_amount,
                    account_2_public_key,
                ),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = LmdbWasmTestBuilder::new(&data_dir);

    let post_state_hash = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit()
        .get_post_state_hash();
    println!("{}", base16::encode_lower(&post_state_hash));
}
