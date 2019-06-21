extern crate casperlabs_engine_grpc_server;
extern crate shared;

use std::path::PathBuf;

use shared::test_utils;

use casperlabs_engine_grpc_server::engine_server::ipc::{
    Deploy, DeployCode, ExecRequest, GenesisRequest,
};
use casperlabs_engine_grpc_server::engine_server::state::{BigInt, ProtocolVersion};

pub const MOCKED_ACCOUNT_ADDRESS: [u8; 32] = [48u8; 32];
pub const COMPILED_WASM_PATH: &str = "../target/wasm32-unknown-unknown/debug";

pub fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    protocol_version.set_value(1);
    protocol_version
}

pub fn get_mock_deploy() -> Deploy {
    let mut deploy = Deploy::new();
    deploy.set_address(MOCKED_ACCOUNT_ADDRESS.to_vec());
    deploy.set_gas_limit(1000);
    deploy.set_gas_price(1);
    deploy.set_nonce(1);
    deploy.set_timestamp(10);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(test_utils::create_empty_wasm_module_bytes());
    deploy.set_session(deploy_code);
    deploy
}

fn get_compiled_wasm_path(contract_file: PathBuf) -> PathBuf {
    let mut path = std::env::current_dir().expect("should get working directory");
    path.push(PathBuf::from(COMPILED_WASM_PATH));
    path.push(contract_file);
    path
}

pub fn read_wasm_file_bytes(contract_file: &str) -> Vec<u8> {
    let contract_file = PathBuf::from(contract_file);
    let path = get_compiled_wasm_path(contract_file);
    std::fs::read(path.clone()).expect(&format!("should read bytes from disk: {:?}", path))
}

pub fn create_genesis_request() -> GenesisRequest {
    let genesis_account_addr = [6u8; 32].to_vec();

    let initial_tokens = {
        let mut ret = BigInt::new();
        ret.set_bit_width(512);
        ret.set_value("1000000".to_string());
        ret
    };

    let mint_code = {
        let mut ret = DeployCode::new();
        let contract_file = "mint_token.wasm";
        let wasm_bytes = read_wasm_file_bytes(contract_file);
        ret.set_code(wasm_bytes);
        ret
    };

    let proof_of_stake_code = {
        let mut ret = DeployCode::new();
        let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
        ret.set_code(wasm_bytes);
        ret
    };

    let protocol_version = {
        let mut ret = ProtocolVersion::new();
        ret.set_value(1);
        ret
    };

    let mut ret = GenesisRequest::new();
    ret.set_address(genesis_account_addr.to_vec());
    ret.set_initial_tokens(initial_tokens);
    ret.set_mint_code(mint_code);
    ret.set_proof_of_stake_code(proof_of_stake_code);
    ret.set_protocol_version(protocol_version);
    ret
}

pub fn create_exec_request(contract_file_name: &str, pre_state_hash: Vec<u8>) -> ExecRequest {
    let bytes_to_deploy = read_wasm_file_bytes(contract_file_name);

    let mut deploy = Deploy::new();
    deploy.set_address([6u8; 32].to_vec());
    deploy.set_gas_limit(1000000000);
    deploy.set_gas_price(1);
    deploy.set_nonce(1);
    deploy.set_timestamp(10);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(bytes_to_deploy);
    deploy.set_session(deploy_code);

    let mut exec_request = ExecRequest::new();
    let mut deploys: protobuf::RepeatedField<Deploy> = <protobuf::RepeatedField<Deploy>>::new();
    deploys.push(deploy);

    exec_request.set_deploys(deploys);
    exec_request.set_parent_state_hash(pre_state_hash.to_vec());
    exec_request.set_protocol_version(get_protocol_version());

    exec_request
}
