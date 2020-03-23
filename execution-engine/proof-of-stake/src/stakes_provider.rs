use crate::{stakes::Stakes, Result};

/// A `StakesProvider` that reads and writes the stakes to/from the contract's known urefs.
pub trait StakesProvider {
    fn read(&self) -> Result<Stakes>;

    fn write(&mut self, stakes: &Stakes);
}
