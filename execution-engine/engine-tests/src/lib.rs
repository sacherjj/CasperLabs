#[cfg(test)]
extern crate grpc;
#[macro_use]
#[cfg(test)]
extern crate lazy_static;
#[cfg(test)]
extern crate protobuf;

#[cfg(test)]
extern crate contract_ffi;
#[cfg(test)]
extern crate engine_core;
#[cfg(test)]
extern crate engine_grpc_server;
#[cfg(test)]
extern crate engine_shared;
#[cfg(test)]
extern crate engine_storage;
#[cfg(test)]
extern crate engine_wasm_prep;

#[cfg(test)]
pub mod support;
#[cfg(test)]
pub mod test;
