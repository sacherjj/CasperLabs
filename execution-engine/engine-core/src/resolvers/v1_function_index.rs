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
    GetKeyFuncIndex = 9,
    GasFuncIndex = 10,
    HasKeyFuncIndex = 11,
    PutKeyFuncIndex = 12,
    StoreFnIndex = 13,
    StoreFnAtHashIndex = 14,
    IsValidFnIndex = 15,
    RevertFuncIndex = 16,
    AddAssociatedKeyFuncIndex = 17,
    RemoveAssociatedKeyFuncIndex = 18,
    UpdateAssociatedKeyFuncIndex = 19,
    SetActionThresholdFuncIndex = 20,
    SerNamedKeysFuncIndex = 21,
    RemoveKeyFuncIndex = 22,
    GetCallerIndex = 23,
    GetBlocktimeIndex = 24,
    CreatePurseIndex = 25,
    TransferToAccountIndex = 26,
    TransferFromPurseToAccountIndex = 27,
    TransferFromPurseToPurseIndex = 28,
    GetBalanceIndex = 29,
    GetPhaseIndex = 30,
    UpgradeContractAtURefIndex = 31,
    GetSystemContractIndex = 32,
    GetMainPurseIndex = 33,
    ReadHostBufferIndex = 34,
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
        let element = FunctionIndex::try_from(19).expect("Unable to create enum from number");
        assert_eq!(element, FunctionIndex::IsValidFnIndex);
    }
    #[test]
    fn enum_to_primitive() {
        let element = FunctionIndex::IsValidFnIndex;
        let primitive: usize = element.into();
        assert_eq!(primitive, 19usize);
    }
    #[test]
    #[should_panic]
    fn invalid_index() {
        FunctionIndex::try_from(123_456_789usize).unwrap();
    }
}
