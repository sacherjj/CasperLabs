#[cfg(test)]
mod metrics;

#[cfg(test)]
pub mod contract_api;
#[cfg(test)]
pub mod deploy;
#[cfg(test)]
pub mod regression;
#[cfg(test)]
pub mod system_contracts;

use contract_ffi::value::U512;
use lazy_static::lazy_static;

lazy_static! {
    static ref DEFAULT_PAYMENT: U512 = 100_000_000.into();
}
