use std::marker::{Send, Sync};

use execution_engine::engine::EngineState;
use ipc::DeployResult;
use ipc_grpc::ExecutionEngineService;
use mappings::*;
use std::collections::HashMap;
use storage::gs::{DbReader, ExecutionEffect};
use storage::history::*;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<R: DbReader, H: History<R>> ipc_grpc::ExecutionEngineService for EngineState<R, H> {
    fn send_deploy(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::Deploy,
    ) -> grpc::SingleResponse<ipc::DeployResult> {
        let mut addr = [0u8; 20];
        addr.copy_from_slice(&p.address);
        let poststate_hash = [0u8; 32];
        match self.run_deploy(&p.session_code, addr, poststate_hash, &(p.gas_limit as u64)) {
            Ok(ee) => {
                let mut res = DeployResult::new();
                res.set_effects(execution_effect_to_ipc(ee));
                grpc::SingleResponse::completed(res)
            }
            //TODO better error handling
            Err(ee_error) => {
                let mut res = DeployResult::new();
                let mut err = ipc::DeployError::new();
                let mut wasm_err = ipc::WasmError::new();
                wasm_err.set_message(format!("{:?}", ee_error));
                err.set_wasmErr(wasm_err);
                res.set_error(err);
                grpc::SingleResponse::completed(res)
            }
        }
    }

    fn execute_effects(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::CommutativeEffects,
    ) -> grpc::SingleResponse<ipc::PostEffectsResult> {
        let mut effects = HashMap::new();
        for entry in p.get_effects().iter() {
            let (k, v) = transform_entry_to_key_transform(entry);
            effects.insert(k, v);
        }
        let r = self.apply_effect(effects);
        match r {
            Ok(post_state_hash) => {
                println!("Effects applied. New state hash is: {:?}", post_state_hash)
            }
            Err(_) => println!("Error {:?} when applying effects", r),
        };

        let res = {
            let mut tmp_res = ipc::PostEffectsResult::new();
            match r {
                Ok(_) => {
                    tmp_res.set_success(ipc::Done::new());
                    tmp_res
                }
                Err(e) => {
                    let mut err = ipc::PostEffectsError::new();
                    err.set_message(format!("{:?}", e));
                    tmp_res.set_error(err);
                    tmp_res
                }
            }
        };
        grpc::SingleResponse::completed(res)
    }

    fn exec(
        &self,
        _o: ::grpc::RequestOptions,
        _p: ipc::ExecRequest,
    ) -> grpc::SingleResponse<ipc::ExecResponse> {
        //TODO: give correct implementation
        let mut response = ipc::ExecResponse::new();
        response.set_missing_parents(ipc::MultipleRootsNotFound::new());
        grpc::SingleResponse::completed(response)
    }

    fn commit(
        &self,
        _o: ::grpc::RequestOptions,
        _p: ipc::CommitRequest,
    ) -> grpc::SingleResponse<ipc::CommitResponse> {
        //TODO: give correct implementation
        let mut response = ipc::CommitResponse::new();
        response.set_missing_prestate(ipc::RootNotFound::new());
        grpc::SingleResponse::completed(response)
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
