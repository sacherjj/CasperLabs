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
    GetKeyFuncIndex = 10,
    GasFuncIndex = 11,
    HasKeyFuncIndex = 12,
    PutKeyFuncIndex = 13,
    StoreFnIndex = 14,
    StoreFnAtHashIndex = 15,
    IsValidFnIndex = 16,
    RevertFuncIndex = 17,
    AddAssociatedKeyFuncIndex = 18,
    RemoveAssociatedKeyFuncIndex = 19,
    UpdateAssociatedKeyFuncIndex = 20,
    SetActionThresholdFuncIndex = 21,
    SerNamedKeysFuncIndex = 22,
    RemoveKeyFuncIndex = 23,
    GetCallerIndex = 24,
    GetBlocktimeIndex = 25,
    CreatePurseIndex = 26,
    TransferToAccountIndex = 27,
    TransferFromPurseToAccountIndex = 28,
    TransferFromPurseToPurseIndex = 29,
    GetBalanceIndex = 30,
    GetPhaseIndex = 31,
    UpgradeContractAtURefIndex = 32,
    GetSystemContractIndex = 33,
    GetMainPurseIndex = 34,
    GetArgSizeFuncIndex = 35,
    ReadHostBufferIndex = 36,
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
