use std::{convert::TryInto, fmt};

use types::api_error::{
    MINT_ERROR_MAX, MINT_ERROR_MIN, POS_ERROR_MAX, POS_ERROR_MIN, USER_ERROR_MAX, USER_ERROR_MIN,
};

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
        if (USER_ERROR_MIN..=USER_ERROR_MAX).contains(&self.0) {
            let code: u16 = (self.0 - USER_ERROR_MIN).try_into().unwrap();
            write!(f, "User error: {}", code)
        } else if (POS_ERROR_MIN..=POS_ERROR_MAX).contains(&self.0) {
            let code: u16 = (self.0 - POS_ERROR_MIN).try_into().unwrap();
            write!(f, "PoS error: {}", code)
        } else if (MINT_ERROR_MIN..=MINT_ERROR_MAX).contains(&self.0) {
            let code: u8 = (self.0 - MINT_ERROR_MIN).try_into().unwrap();
            write!(f, "Mint error: {}", code)
        } else {
            write!(f, "Exit code: {}", self.0)
        }
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
    use types::{
        api_error::ApiError,
        system_contract_errors::{mint::Error as MintError, pos::Error as PosError},
    };

    const REVERT42: Revert = Revert::new(42);

    #[test]
    fn should_format_standard_exit_code() {
        assert_eq!(REVERT42.to_string(), "Exit code: 42");
    }

    #[test]
    fn should_format_standard_exit_code_zero() {
        assert_eq!(Revert::new(0).to_string(), "Exit code: 0");
    }

    #[test]
    fn should_format_standard_exit_code_large() {
        let value = u32::max_value();
        assert_eq!(
            Revert::new(value).to_string(),
            format!("Exit code: {}", value)
        );
    }

    #[test]
    fn should_format_as_exit_code_past_user_error_range() {
        assert_eq!(
            Revert::new(USER_ERROR_MAX + 1).to_string(),
            "Exit code: 131072"
        );
    }

    #[test]
    fn should_format_first_user_error_code() {
        let user = ApiError::User(0);
        let revert_status: Revert = user.into();
        assert_eq!(revert_status.to_string(), "User error: 0");
    }

    #[test]
    fn should_format_first_mint_error_code() {
        let error = ApiError::from(MintError::InsufficientFunds);
        let revert_status: Revert = error.into();
        assert_eq!(revert_status.to_string(), "Mint error: 0");
    }

    #[test]
    fn should_format_last_mint_error_code() {
        let error = ApiError::from(MintError::PurseNotFound);
        let revert_status: Revert = error.into();
        assert_eq!(revert_status.to_string(), "Mint error: 7");
    }

    #[test]
    fn should_format_last_mint_reserved_error_code() {
        let revert_status = Revert::new(MINT_ERROR_MIN + 255);
        assert_eq!(revert_status.to_string(), "Mint error: 255");
    }

    #[test]
    fn should_format_first_pos_error_code() {
        let pos_error = PosError::NotBonded;
        let error = ApiError::from(pos_error);
        let revert_status: Revert = error.into();
        assert_eq!(
            revert_status.to_string(),
            format!("PoS error: {}", pos_error as u8)
        );
    }

    #[test]
    fn should_format_last_pos_error_code() {
        let pos_error = PosError::SetRefundPurseCalledOutsidePayment;
        let error = ApiError::from(pos_error);
        let revert_status: Revert = error.into();
        assert_eq!(
            revert_status.to_string(),
            format!("PoS error: {}", pos_error as u8)
        );
    }

    #[test]
    fn should_format_last_pos_reserved_error_code() {
        let revert_status = Revert::new(POS_ERROR_MIN + 255);
        assert_eq!(revert_status.to_string(), "PoS error: 255");
    }

    #[test]
    fn should_format_last_user_error_code() {
        let user = ApiError::User(u16::max_value());
        let revert_status: Revert = user.into();
        assert_eq!(revert_status.to_string(), "User error: 65535");
    }
}
