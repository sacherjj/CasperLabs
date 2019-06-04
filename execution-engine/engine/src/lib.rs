#![feature(result_map_or_else)]
#![feature(never_type)]

// core dependencies
extern crate core;

// third-party dependencies
extern crate blake2;
extern crate failure;
extern crate itertools;
extern crate linked_hash_map;
extern crate parity_wasm;
extern crate parking_lot;
extern crate pwasm_utils;
extern crate rand;
extern crate rand_chacha;
extern crate wasmi;

// internal dependencies
extern crate common;
extern crate shared;
extern crate storage;
extern crate wasm_prep;

// third-party dev-dependencies
#[cfg(test)]
#[macro_use]
extern crate matches;
#[cfg(test)]
extern crate proptest;

pub mod args;
pub mod byte_size;
pub mod engine_state;
pub mod execution;
pub mod functions;
pub mod meter;
pub mod resolvers;
pub mod runtime_context;
pub mod tracking_copy;
pub mod utils;

type URefAddr = [u8; 32];
