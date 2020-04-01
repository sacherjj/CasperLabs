use std::fmt;

/// A [[Revert]] instance represents a status code of reverted execution.
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub struct Revert(u32);

impl Revert {
    pub const fn new(value: u32) -> Revert {
        Revert(value)
    }

    pub fn value(self) -> u32 {
        self.0
    }
}

impl fmt::Display for Revert {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "Exit code: {}", self.0)
    }
}

impl<T: Into<u32>> From<T> for Revert {
    fn from(value: T) -> Revert {
        Revert::new(value.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REVERT42: Revert = Revert::new(42);

    #[test]
    fn should_format_standard_exit_code() {
        assert_eq!(REVERT42.to_string(), "Exit code: 42");
    }
}
