use core::fmt::Debug;

use crate::ApiError;

pub type TransferResult = Result<TransferredTo, ApiError>;

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
#[repr(i32)]
pub enum TransferredTo {
    ExistingAccount = 0,
    NewAccount = 1,
}

impl TransferredTo {
    pub fn result_from(value: i32) -> TransferResult {
        match value {
            x if x == TransferredTo::ExistingAccount as i32 => Ok(TransferredTo::ExistingAccount),
            x if x == TransferredTo::NewAccount as i32 => Ok(TransferredTo::NewAccount),
            _ => Err(ApiError::Transfer),
        }
    }

    pub fn i32_from(result: TransferResult) -> i32 {
        match result {
            Ok(transferred_to) => transferred_to as i32,
            Err(_) => 2,
        }
    }
}
