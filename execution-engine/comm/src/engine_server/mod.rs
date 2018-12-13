pub mod ipc;
pub mod ipc_grpc;

use execution_engine::engine::EngineState;
use ipc::DeployResult;
use ipc_grpc::ExecutionEngineService;
use std::marker::{Send, Sync};
use storage::{GlobalState, TrackingCopy};

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<T: TrackingCopy, G: GlobalState<T>> ipc_grpc::ExecutionEngineService for EngineState<T, G> {
    fn send_deploy(
        &self,
        o: ::grpc::RequestOptions,
        p: ipc::Deploy,
    ) -> grpc::SingleResponse<ipc::DeployResult> {
        let mut addr = [0u8; 20];
        addr.copy_from_slice(&p.address);
        match self.run_deploy(&p.session_code, addr) {
            Ok(_) => {
                let mut res = DeployResult::new();
                res.set_effects(ipc::ExecutionEffect::new());
                grpc::SingleResponse::completed(res)
            }
            Err(_) => {
                let mut res = DeployResult::new();
                let mut err = ipc::DeployError::new();
                err.set_wasmErr(ipc::WasmError::new());
                res.set_error(err);
                grpc::SingleResponse::completed(res)
            }
        }
    }

    fn execute_effects(
        &self,
        o: ::grpc::RequestOptions,
        p: ipc::CommutativeEffects,
    ) -> grpc::SingleResponse<ipc::PostEffectsResult> {
        // TODO: should be replaced with the calls to the engine (self)
        grpc::SingleResponse::completed(ipc::PostEffectsResult::new())
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
    server.http.set_unix_addr(socket.to_owned());
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
