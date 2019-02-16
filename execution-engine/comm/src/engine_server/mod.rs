use std::marker::{Send, Sync};

use execution_engine::engine::EngineState;
use ipc::*;
use ipc_grpc::ExecutionEngineService;
use mappings::*;
use std::collections::HashMap;
use storage::gs::DbReader;
use storage::history::*;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<R: DbReader, H: History<R>> ipc_grpc::ExecutionEngineService for EngineState<R, H> {
    fn exec(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::ExecRequest,
    ) -> grpc::SingleResponse<ipc::ExecResponse> {
        let mut prestate_hash = [0u8; 32];
        prestate_hash.copy_from_slice(&p.get_parent_state_hash());
        let deploys = p.get_deploys();
        let mut exec_response = ipc::ExecResponse::new();
        let mut exec_result = ipc::ExecResult::new();
        let mut deploy_results: Vec<DeployResult> = Vec::new();
        for deploy in deploys.into_iter() {
            let module_bytes = &deploy.session_code;
            let mut address: [u8; 20] = [0u8; 20];
            address.copy_from_slice(&deploy.address);
            let timestamp = deploy.timestamp;
            let nonce = deploy.nonce;
            let gas_limit = deploy.gas_limit as u64;
            match self.run_deploy(
                module_bytes,
                address,
                timestamp,
                nonce,
                prestate_hash,
                &gas_limit,
            ) {
                Ok(effects) => {
                    let ipc_ee = execution_effect_to_ipc(effects);
                    let mut deploy_result = ipc::DeployResult::new();
                    deploy_result.set_effects(ipc_ee);
                    deploy_results.push(deploy_result);
                }
                Err(err) => {
                    //TODO(mateusz.gorski) Better error handling and tests!
                    let mut deploy_result = ipc::DeployResult::new();
                    let mut error = ipc::DeployError::new();
                    let mut wasm_error = ipc::WasmError::new();
                    wasm_error.set_message(format!("{:?}", err));
                    error.set_wasmErr(wasm_error);
                    deploy_result.set_error(error);
                    deploy_results.push(deploy_result);
                }
            };
        }
        exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
        exec_response.set_success(exec_result);
        grpc::SingleResponse::completed(exec_response)
    }

    fn commit(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::CommitRequest,
    ) -> grpc::SingleResponse<ipc::CommitResponse> {
        let mut prestate_hash = [0u8; 32];
        prestate_hash.copy_from_slice(&p.get_prestate_hash());
        let mut effects = HashMap::new();
        for entry in p.get_effects().iter() {
            let (k, v) = transform_entry_to_key_transform(entry);
            effects.insert(k, v);
        }
        let r = self.apply_effect(prestate_hash, effects);
        let result = {
            let mut tmp_res = ipc::CommitResponse::new();
            match r {
                Ok(post_state_hash) => {
                    println!("Effects applied. New state hash is: {:?}", post_state_hash);
                    let mut commit_result = ipc::CommitResult::new();
                    commit_result.set_poststate_hash(post_state_hash.to_vec());
                    tmp_res.set_success(commit_result);
                }
                Err(e) => {
                    println!("Error {:?} when applying effects", e);
                    let mut err = ipc::PostEffectsError::new();
                    err.set_message(format!("{:?}", e));
                    tmp_res.set_failed_transform(err);
                }
            };
            tmp_res
        };
        grpc::SingleResponse::completed(result)
    }
}

pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    e: E,
) -> grpc::ServerBuilder {
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let mut server = grpc::ServerBuilder::new_plain();
    server.http.set_unix_addr(socket.to_owned()).unwrap();
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
