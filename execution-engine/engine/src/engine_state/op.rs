#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Op {
    Read,
    Write,
    Add,
    NoOp,
}

impl std::ops::Add for Op {
    type Output = Op;

    fn add(self, other: Op) -> Op {
        match (self, other) {
            (a, Op::NoOp) => a,
            (Op::NoOp, b) => b,
            (Op::Read, Op::Read) => Op::Read,
            (Op::Add, Op::Add) => Op::Add,
            _ => Op::Write,
        }
    }
}

impl std::fmt::Display for Op {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}
