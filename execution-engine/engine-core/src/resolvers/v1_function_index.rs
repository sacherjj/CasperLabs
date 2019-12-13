use std::convert::TryFrom;

use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::{FromPrimitive, ToPrimitive};

#[derive(Debug, PartialEq, FromPrimitive, ToPrimitive)]
#[repr(usize)]
pub enum FunctionIndex {
    WriteFuncIndex = 0,
    WriteLocalFuncIndex = 1,
    ReadFuncIndex = 2,
    ReadLocalFuncIndex = 3,
    AddFuncIndex = 4,
    NewFuncIndex = 5,
    RetFuncIndex = 6,
    CallContractFuncIndex = 7,
    LoadArgFuncIndex = 8,
    GetArgFuncIndex = 9,
    RetFuncIndex = 10,
    GetCallResultFuncIndex = 11,
    CallContractFuncIndex = 12,
    GetKeyFuncIndex = 13,
    GasFuncIndex = 14,
    HasKeyFuncIndex = 15,
    PutKeyFuncIndex = 16,
    StoreFnIndex = 17,
    StoreFnAtHashIndex = 18,
    IsValidFnIndex = 19,
    RevertFuncIndex = 20,
    AddAssociatedKeyFuncIndex = 21,
    RemoveAssociatedKeyFuncIndex = 22,
    UpdateAssociatedKeyFuncIndex = 23,
    SetActionThresholdFuncIndex = 24,
    SerNamedKeysFuncIndex = 25,
    ListNamedKeysFuncIndex = 26,
    RemoveKeyFuncIndex = 27,
    GetCallerIndex = 28,
    GetBlocktimeIndex = 29,
    CreatePurseIndex = 30,
    TransferToAccountIndex = 31,
    TransferFromPurseToAccountIndex = 32,
    TransferFromPurseToPurseIndex = 33,
    GetBalanceIndex = 34,
    GetPhaseIndex = 35,
    UpgradeContractAtURefIndex = 36,
    GetSystemContractIndex = 37,
    GetMainPurseIndex = 38,
    GetArgSizeFuncIndex = 39,
    ReadHostBufferIndex = 40,
}

impl Into<usize> for FunctionIndex {
    fn into(self) -> usize {
        // NOTE: This can't fail as `FunctionIndex` is represented by usize,
        // so this serves mostly as a syntax sugar.
        self.to_usize().unwrap()
    }
}

impl TryFrom<usize> for FunctionIndex {
    type Error = &'static str;
    fn try_from(value: usize) -> Result<Self, Self::Error> {
        FromPrimitive::from_usize(value).ok_or("Invalid function index")
    }
}

#[cfg(test)]
mod tests {
    use super::FunctionIndex;
    use std::convert::TryFrom;

    #[test]
    fn primitive_to_enum() {
        FunctionIndex::try_from(19).expect("Unable to create enum from number");
    }
    #[test]
    fn enum_to_primitive() {
        let element = FunctionIndex::UpdateAssociatedKeyFuncIndex;
        let _primitive: usize = element.into();
    }
    #[test]
    fn invalid_index() {
        assert!(FunctionIndex::try_from(123_456_789usize).is_err());
    }
}
