//! This executable is designed to be run to set up global state in preparation for running other
//! standalone test executable(s).  This will allow profiling to be done on executables running only
//! meaningful code, rather than including test setup effort in the profile results.

use std::collections::HashMap;
use std::env;
use std::path::PathBuf;

use clap::{crate_version, App};

use casperlabs_engine_tests::support::profiling_common;
use casperlabs_engine_tests::support::test_support::{
    DeployBuilder, ExecRequestBuilder, LmdbWasmTestBuilder,
};
use contract_ffi::base16;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const ABOUT: &str = "Initializes global state in preparation for profiling runs. Outputs the root \
                     hash from the commit response.";
const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";

fn data_dir() -> PathBuf {
    let exe_name = profiling_common::exe_name();
    let data_dir_arg = profiling_common::data_dir_arg();
    let arg_matches = App::new(&exe_name)
        .version(crate_version!())
        .about(ABOUT)
        .arg(data_dir_arg)
        .get_matches();
    profiling_common::data_dir(&arg_matches)
}

fn main() {
    let data_dir = data_dir();

    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = profiling_common::account_1_public_key();
    let account_1_initial_amount = profiling_common::account_1_initial_amount();
    let account_2_public_key = profiling_common::account_2_public_key();

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "state_initializer.wasm",
                (
                    account_1_public_key,
                    account_1_initial_amount,
                    account_2_public_key,
                ),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = LmdbWasmTestBuilder::new(&data_dir);

    let post_state_hash = builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit()
        .get_post_state_hash();
    println!("{}", base16::encode_lower(&post_state_hash));
}
