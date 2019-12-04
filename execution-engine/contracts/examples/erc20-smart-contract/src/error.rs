use contract_ffi::contract_api::Error as ApiError;

#[repr(u16)]
pub enum Error {
    UnknownApiCommand = 1,                      // 65537
    UnknownDeployCommand = 2,                   // 65538
    UnknownProxyCommand = 3,                    // 65539
    UnknownErc20ConstructorCommand = 4,         // 65540
    UnknownErc20CallCommand = 5,                // 65541
    BalanceAssertionFailure = 6,                // 65542
    TotalSupplyAssertionFailure = 7,            // 65543
    AllowanceAssertionFailure = 8,              // 65544
    TransferFailureNotEnoughBalance = 9,        // 65545
    TransferFromFailureNotEnoughBalance = 10,   // 65546
    TransferFromFailureNotEnoughAllowance = 11, // 65547
}

impl From<Error> for ApiError {
    fn from(error: Error) -> ApiError {
        ApiError::User(error as u16)
    }
}
