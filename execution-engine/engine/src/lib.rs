#![feature(result_map_or_else)]
#![feature(never_type)]
extern crate common;
extern crate core;
extern crate failure;
extern crate itertools;
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
pub mod runtime_context;
pub mod trackingcopy;

use std::ops::Deref;

mod utils;

#[cfg(test)]
#[macro_use]
extern crate matches;

#[cfg(test)]
extern crate proptest;

type URefAddr = [u8; 32];

/// Newtype used for differentiating between plain T and validated T.
/// What validation means is left purposefully vague as it may depend on the context.
#[derive(Clone)]
pub struct Validated<T: Clone>(pub T);

impl<T: Clone> Deref for Validated<T> {
    type Target = T;
    fn deref(&self) -> &T {
        &self.0
    }
}
