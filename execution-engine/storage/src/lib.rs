#![feature(never_type, result_map_or_else)]

// core dependencies
extern crate core;

// third-party dependencies
#[macro_use]
extern crate failure;
extern crate lmdb;
extern crate parking_lot;
extern crate wasmi;

// local dependencies
extern crate common;
extern crate shared;

// third-party dev-dependencies
#[cfg(test)]
#[macro_use]
extern crate lazy_static;
#[cfg(test)]
extern crate proptest;
#[cfg(test)]
extern crate tempfile;

// modules
pub mod error;
pub mod global_state;
pub mod trie;
pub mod trie_store;
