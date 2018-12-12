pub mod ipc;
pub mod ipc_grpc;

use execution_engine::engine::Engine;
use ipc::DeployResult;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl ipc_grpc::ExecutionEngineService for Engine {
    fn send_deploy(
        &self,
        o: ::grpc::RequestOptions,
        p: ipc::Deploy,
    ) -> grpc::SingleResponse<ipc::DeployResult> {
        // TODO: should be replaced with the calls to the engine (self)
        grpc::SingleResponse::completed(DeployResult::new())
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

pub fn new(socket: &str, e: Engine) -> grpc::ServerBuilder {
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
