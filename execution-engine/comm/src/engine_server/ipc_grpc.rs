// This file is generated. Do not edit
// @generated

// https://github.com/Manishearth/rust-clippy/issues/702
#![allow(unknown_lints)]
#![allow(clippy)]

#![cfg_attr(rustfmt, rustfmt_skip)]

#![allow(box_pointers)]
#![allow(dead_code)]
#![allow(missing_docs)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
#![allow(non_upper_case_globals)]
#![allow(trivial_casts)]
#![allow(unsafe_code)]
#![allow(unused_imports)]
#![allow(unused_results)]


// interface

pub trait ExecutionEngineService {
    fn send_deploy(&self, o: ::grpc::RequestOptions, p: super::ipc::Deploy) -> ::grpc::SingleResponse<super::ipc::DeployResult>;

    fn execute_effects(&self, o: ::grpc::RequestOptions, p: super::ipc::CommutativeEffects) -> ::grpc::SingleResponse<super::ipc::PostEffectsResult>;
}

// client

pub struct ExecutionEngineServiceClient {
    grpc_client: ::std::sync::Arc<::grpc::Client>,
    method_SendDeploy: ::std::sync::Arc<::grpc::rt::MethodDescriptor<super::ipc::Deploy, super::ipc::DeployResult>>,
    method_ExecuteEffects: ::std::sync::Arc<::grpc::rt::MethodDescriptor<super::ipc::CommutativeEffects, super::ipc::PostEffectsResult>>,
}

impl ::grpc::ClientStub for ExecutionEngineServiceClient {
    fn with_client(grpc_client: ::std::sync::Arc<::grpc::Client>) -> Self {
        ExecutionEngineServiceClient {
            grpc_client: grpc_client,
            method_SendDeploy: ::std::sync::Arc::new(::grpc::rt::MethodDescriptor {
                name: "/io.casperlabs.ipc.ExecutionEngineService/SendDeploy".to_string(),
                streaming: ::grpc::rt::GrpcStreaming::Unary,
                req_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                resp_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
            }),
            method_ExecuteEffects: ::std::sync::Arc::new(::grpc::rt::MethodDescriptor {
                name: "/io.casperlabs.ipc.ExecutionEngineService/ExecuteEffects".to_string(),
                streaming: ::grpc::rt::GrpcStreaming::Unary,
                req_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                resp_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
            }),
        }
    }
}

impl ExecutionEngineService for ExecutionEngineServiceClient {
    fn send_deploy(&self, o: ::grpc::RequestOptions, p: super::ipc::Deploy) -> ::grpc::SingleResponse<super::ipc::DeployResult> {
        self.grpc_client.call_unary(o, p, self.method_SendDeploy.clone())
    }

    fn execute_effects(&self, o: ::grpc::RequestOptions, p: super::ipc::CommutativeEffects) -> ::grpc::SingleResponse<super::ipc::PostEffectsResult> {
        self.grpc_client.call_unary(o, p, self.method_ExecuteEffects.clone())
    }
}

// server

pub struct ExecutionEngineServiceServer;


impl ExecutionEngineServiceServer {
    pub fn new_service_def<H : ExecutionEngineService + 'static + Sync + Send + 'static>(handler: H) -> ::grpc::rt::ServerServiceDefinition {
        let handler_arc = ::std::sync::Arc::new(handler);
        ::grpc::rt::ServerServiceDefinition::new("/io.casperlabs.ipc.ExecutionEngineService",
            vec![
                ::grpc::rt::ServerMethod::new(
                    ::std::sync::Arc::new(::grpc::rt::MethodDescriptor {
                        name: "/io.casperlabs.ipc.ExecutionEngineService/SendDeploy".to_string(),
                        streaming: ::grpc::rt::GrpcStreaming::Unary,
                        req_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                        resp_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                    }),
                    {
                        let handler_copy = handler_arc.clone();
                        ::grpc::rt::MethodHandlerUnary::new(move |o, p| handler_copy.send_deploy(o, p))
                    },
                ),
                ::grpc::rt::ServerMethod::new(
                    ::std::sync::Arc::new(::grpc::rt::MethodDescriptor {
                        name: "/io.casperlabs.ipc.ExecutionEngineService/ExecuteEffects".to_string(),
                        streaming: ::grpc::rt::GrpcStreaming::Unary,
                        req_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                        resp_marshaller: Box::new(::grpc::protobuf::MarshallerProtobuf),
                    }),
                    {
                        let handler_copy = handler_arc.clone();
                        ::grpc::rt::MethodHandlerUnary::new(move |o, p| handler_copy.execute_effects(o, p))
                    },
                ),
            ],
        )
    }
}
