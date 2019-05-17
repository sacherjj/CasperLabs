#![feature(never_type, result_map_or_else)]

#[macro_use]
extern crate failure;

extern crate common;
extern crate lmdb;
extern crate num;
extern crate parking_lot;
extern crate shared;
extern crate wasmi;

#[cfg(test)]
#[macro_use]
extern crate lazy_static;

#[cfg(test)]
extern crate proptest;

extern crate core;
#[cfg(test)]
extern crate tempfile;

pub mod error;
pub mod global_state;
pub mod history;
pub mod op;
pub mod transform;
