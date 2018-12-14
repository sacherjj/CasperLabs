use std::fmt;
use std::ops::Add;

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Op {
    Read,
    Write,
    Add,
    NoOp,
}

use self::Op::*;

impl Add for Op {
    type Output = Op;

    fn add(self, other: Op) -> Op {
        match (self, other) {
            (a, NoOp) => a,
            (NoOp, b) => b,
            (Read, Read) => Read,
            (Add, Add) => Add,
            _ => Write,
        }
    }
}

impl fmt::Display for Op {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}
