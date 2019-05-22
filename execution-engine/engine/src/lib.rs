#![feature(result_map_or_else)]
#![feature(never_type)]
extern crate blake2;
extern crate common;
extern crate core;
extern crate failure;
extern crate itertools;
extern crate linked_hash_map;
extern crate parity_wasm;
extern crate parking_lot;
extern crate pwasm_utils;
extern crate rand;
extern crate rand_chacha;
extern crate shared;
extern crate storage;
extern crate wasm_prep;
extern crate wasmi;

pub mod engine;
pub mod args;
pub mod byte_size;
pub mod execution;
pub mod functions;
pub mod meter;
pub mod resolvers;
pub mod runtime_context;
pub mod tracking_copy;

mod utils;

#[cfg(test)]
#[macro_use]
extern crate matches;

#[cfg(test)]
extern crate proptest;

type URefAddr = [u8; 32];
