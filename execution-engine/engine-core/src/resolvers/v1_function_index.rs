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
    AddLocalFuncIndex = 5,
    NewFuncIndex = 6,
    RetFuncIndex = 7,
    CallContractFuncIndex = 8,
    LoadArgFuncIndex = 9,
    GetArgFuncIndex = 10,
    GetKeyFuncIndex = 11,
    GasFuncIndex = 12,
    HasKeyFuncIndex = 13,
    PutKeyFuncIndex = 14,
    StoreFnIndex = 15,
    StoreFnAtHashIndex = 16,
    IsValidURefFnIndex = 17,
    RevertFuncIndex = 18,
    AddAssociatedKeyFuncIndex = 19,
    RemoveAssociatedKeyFuncIndex = 20,
    UpdateAssociatedKeyFuncIndex = 21,
    SetActionThresholdFuncIndex = 22,
    LoadNamedKeysFuncIndex = 23,
    RemoveKeyFuncIndex = 24,
    GetCallerIndex = 25,
    GetBlocktimeIndex = 26,
    CreatePurseIndex = 27,
    TransferToAccountIndex = 28,
    TransferFromPurseToAccountIndex = 29,
    TransferFromPurseToPurseIndex = 30,
    GetBalanceIndex = 31,
    GetPhaseIndex = 32,
    UpgradeContractAtURefIndex = 33,
    GetSystemContractIndex = 34,
    GetMainPurseIndex = 35,
    GetArgSizeFuncIndex = 36,
    ReadHostBufferIndex = 37,
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
