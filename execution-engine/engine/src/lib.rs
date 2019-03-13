#![feature(result_map_or_else)]
extern crate common;
extern crate core;
extern crate parity_wasm;
extern crate parking_lot;
extern crate pwasm_utils;
extern crate rand;
extern crate rand_chacha;
extern crate shared;
extern crate storage;
extern crate vm;
extern crate wasm_prep;
extern crate wasmi;

pub mod argsparser;
pub mod engine;
pub mod execution;
