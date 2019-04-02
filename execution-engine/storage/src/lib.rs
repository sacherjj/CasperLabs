#![feature(never_type, result_map_or_else)]

#[macro_use]
extern crate failure;

extern crate common;
extern crate num;
extern crate parking_lot;
extern crate lmdb;
extern crate shared;
extern crate wasmi;

#[cfg(test)]
extern crate gens;

#[cfg(test)]
extern crate proptest;

#[cfg(test)]
extern crate tempfile;

pub mod error;
pub mod gs;
pub mod history;
pub mod op;
pub mod transform;
