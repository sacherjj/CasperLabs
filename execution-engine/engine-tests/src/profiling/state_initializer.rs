//! This executable is designed to be run to set up global state in preparation for running other
//! standalone test executable(s).  This will allow profiling to be done on executables running only
//! meaningful code, rather than including test setup effort in the profile results.

use std::{env, path::PathBuf};

use clap::{crate_version, App};

use engine_core::engine_state::{
    engine_config::EngineConfig, genesis::ExecConfig, run_genesis_request::RunGenesisRequest,
};
use engine_test_support::{
    internal::{
        utils, DeployItemBuilder, ExecuteRequestBuilder, LmdbWasmTestBuilder, DEFAULT_ACCOUNTS,
        DEFAULT_GENESIS_CONFIG_HASH, DEFAULT_PAYMENT, DEFAULT_PROTOCOL_VERSION, DEFAULT_WASM_COSTS,
        MINT_INSTALL_CONTRACT, POS_INSTALL_CONTRACT, STANDARD_PAYMENT_CONTRACT,
        STANDARD_PAYMENT_INSTALL_CONTRACT,
    },
    DEFAULT_ACCOUNT_ADDR,
};

use casperlabs_engine_tests::profiling;

const ABOUT: &str = "Initializes global state in preparation for profiling runs. Outputs the root \
                     hash from the commit response.";
const STATE_INITIALIZER_CONTRACT: &str = "state_initializer.wasm";

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

    let genesis_public_key = DEFAULT_ACCOUNT_ADDR;
    let account_1_public_key = profiling::account_1_public_key();
    let account_1_initial_amount = profiling::account_1_initial_amount();
    let account_2_public_key = profiling::account_2_public_key();

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                STATE_INITIALIZER_CONTRACT,
                (
                    account_1_public_key,
                    account_1_initial_amount,
                    account_2_public_key,
                ),
            )
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let engine_config = EngineConfig::new().with_use_system_contracts(true);
    let mut builder = LmdbWasmTestBuilder::new_with_config(&data_dir, engine_config);

    let mint_installer_bytes = utils::read_wasm_file_bytes(MINT_INSTALL_CONTRACT);
    let pos_installer_bytes = utils::read_wasm_file_bytes(POS_INSTALL_CONTRACT);
    let standard_payment_installer_bytes =
        utils::read_wasm_file_bytes(STANDARD_PAYMENT_INSTALL_CONTRACT);
    let exec_config = ExecConfig::new(
        mint_installer_bytes,
        pos_installer_bytes,
        standard_payment_installer_bytes,
        DEFAULT_ACCOUNTS.clone(),
        *DEFAULT_WASM_COSTS,
    );
    let run_genesis_request = RunGenesisRequest::new(
        *DEFAULT_GENESIS_CONFIG_HASH,
        *DEFAULT_PROTOCOL_VERSION,
        exec_config,
    );

    let post_state_hash = builder
        .run_genesis(&run_genesis_request)
        .exec(exec_request)
        .expect_success()
        .commit()
        .get_post_state_hash();
    println!("{}", base16::encode_lower(&post_state_hash));
}
