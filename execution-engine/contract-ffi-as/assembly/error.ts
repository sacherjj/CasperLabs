import * as externals from "./externals";

const SYSTEM_CONTRACT_ERROR_CODE_OFFSET: u32 = 65024;
const POS_ERROR_CODE_OFFSET: u32 = 65280;
const USER_ERROR_CODE_OFFSET: u32 = 65535;

export const enum ErrorCode {
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

export const enum PosErrorCode {
    NotBonded = 0,
    TooManyEventsInQueue = 1,
    CannotUnbondLastValidator = 2,
    SpreadTooHigh = 3,
    MultipleRequests = 4,
    BondTooSmall = 5,
    BondTooLarge = 6,
    UnbondTooLarge = 7,
    BondTransferFailed = 8,
    UnbondTransferFailed = 9,
    MissingArgument = 10,
    InvalidArgument = 11,
    TimeWentBackwards = 12,
    StakesNotFound = 13,
    PaymentPurseNotFound = 14,
    PaymentPurseKeyUnexpectedType = 15,
    PaymentPurseBalanceNotFound = 16,
    BondingPurseNotFound = 17,
    BondingPurseKeyUnexpectedType = 18,
    RefundPurseKeyUnexpectedType = 19,
    RewardsPurseNotFound = 20,
    RewardsPurseKeyUnexpectedType = 21,
    QueueNotStoredAsByteArray = 22,
    QueueDeserializationFailed = 23,
    QueueDeserializationExtraBytes = 24,
    StakesKeyDeserializationFailed = 25,
    StakesDeserializationFailed = 26,
    SystemFunctionCalledByUserAccount = 27,
    InsufficientPaymentForAmountSpent = 28,
    FailedTransferToRewardsPurse = 29,
    FailedTransferToAccountPurse = 30,
    SetRefundPurseCalledOutsidePayment = 31,
}

export class Error{
    private errorCodeValue: u32;

    constructor(value: u32) {
        this.errorCodeValue = value;
    }

    static fromResult(result: u32): Error | null {
        if (result == 0) {
            // Ok
            return null;
        }
        return new Error(result);
    }

    static fromUserError(userErrorCodeValue: u16): Error {
        return new Error(USER_ERROR_CODE_OFFSET + 1 + userErrorCodeValue);
    }

    static fromErrorCode(errorCode: ErrorCode): Error {
        return new Error(<u32>errorCode);
    }

    static fromPosErrorCode(errorCode: PosErrorCode): Error {
        return new Error(<u32>errorCode + POS_ERROR_CODE_OFFSET);
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
