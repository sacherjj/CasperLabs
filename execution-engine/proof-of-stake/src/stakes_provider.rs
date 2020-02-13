use crate::{stakes::Stakes, Result};

pub trait StakesProvider {
    fn read() -> Result<Stakes>;

    fn write(stakes: &Stakes);
}
