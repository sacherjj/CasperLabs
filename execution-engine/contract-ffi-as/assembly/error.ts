import * as externals from "./externals";

const SYSTEM_CONTRACT_ERROR_CODE_OFFSET: u32 = 65024;
const USER_ERROR_CODE_OFFSET: u32 = 65535;

export const enum ErrorCode{
    None = 1,
    MissingArgument = 2,
    InvalidArgument = 3,
    Deserialize = 4,
    Read = 5,
    ValueNotFound = 6,
    ContractNotFound = 7,
    GetKey = 8,
    UnexpectedKeyVariant = 9,
    UnexpectedValueVariant = 10,
    UnexpectedContractRefVariant = 11,
    InvalidPurseName = 12,
    InvalidPurse = 13,
    UpgradeContractAtURef = 14,
    Transfer = 15,
    NoAccessRights = 16,
    ValueConversion = 17,
    CLTypeMismatch = 18,
    EarlyEndOfStream = 19,
    FormattingError = 20,
    LeftOverBytes = 21,
    OutOfMemoryError = 22,
    MaxKeysLimit = 23,
    DuplicateKey = 24,
    PermissionDenied = 25,
    MissingKey = 26,
    ThresholdViolation = 27,
    KeyManagementThresholdError = 28,
    DeploymentThresholdError = 29,
    PermissionDeniedError = 30,
    InsufficientTotalWeight = 31,
    InvalidSystemContract = 32,
    PurseNotCreated = 33,
    Unhandled = 34,
    BufferTooSmall = 35,
    HostBufferEmpty = 36,
    HostBufferFull = 37,
}

export class Error{
    private errorCodeValue: u32;

    constructor(userErrorCodeValue: u32) {
        if(userErrorCodeValue <= USER_ERROR_CODE_OFFSET) {
            this.errorCodeValue = userErrorCodeValue + USER_ERROR_CODE_OFFSET;
        }else{
            this.errorCodeValue = userErrorCodeValue;
        }
    }

    static fromErrorCode(errorCode: ErrorCode): Error {
        return new Error(<u32>errorCode);
    }

    value(): u32{
        return this.errorCodeValue;
    }

    isUserError(): bool{
        return this.errorCodeValue > USER_ERROR_CODE_OFFSET;
    }

    isSystemContractError(): bool{
        return this.errorCodeValue >= SYSTEM_CONTRACT_ERROR_CODE_OFFSET && this.errorCodeValue <= USER_ERROR_CODE_OFFSET;
    }

    revert(): void {
        externals.revert(this.errorCodeValue);
    }
}
