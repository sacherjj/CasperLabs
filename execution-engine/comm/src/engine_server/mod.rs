use execution_engine::engine::EngineState;
use std::marker::{Send, Sync};
use storage::gs::{GlobalState, TrackingCopy};

pub mod mappings;
use mappings::*;

pub mod ipc;
pub mod ipc_grpc;

use ipc::DeployResult;
use ipc_grpc::ExecutionEngineService;
// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<T: TrackingCopy, G: GlobalState<T>> ipc_grpc::ExecutionEngineService for EngineState<T, G> {
    fn send_deploy(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::Deploy,
    ) -> grpc::SingleResponse<ipc::DeployResult> {
        let mut addr = [0u8; 20];
        addr.copy_from_slice(&p.address);
        match self.run_deploy(&p.session_code, addr, &(p.gas_limit as u64)) {
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
        let r: Result<(), execution_engine::engine::Error> = p
            .get_effects()
            .iter()
            .map(|e| transform_entry_to_key_transform(e))
            .try_fold((), |_, (k, t)| {
                let res = self.apply_effect(k, t);
                match &res {
                    //TODO: instead of println! this should be logged
                    Ok(_) => println!("Applied effects for {:?}", k),
                    Err(e) => println!("Error {:?} when applying effects for {:?}", e, k),
                };
                res
            });
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
