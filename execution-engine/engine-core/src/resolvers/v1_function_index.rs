use std::convert::TryFrom;

use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::{FromPrimitive, ToPrimitive};

#[derive(Debug, PartialEq, FromPrimitive, ToPrimitive)]
#[repr(usize)]
pub enum FunctionIndex {
    WriteFuncIndex,
    WriteLocalFuncIndex,
    ReadFuncIndex,
    ReadLocalFuncIndex,
    AddFuncIndex,
    AddLocalFuncIndex,
    NewFuncIndex,
    RetFuncIndex,
    CallContractFuncIndex,
    GetArgFuncIndex,
    GetKeyFuncIndex,
    GasFuncIndex,
    HasKeyFuncIndex,
    PutKeyFuncIndex,
    StoreFnIndex,
    StoreFnAtHashIndex,
    IsValidURefFnIndex,
    RevertFuncIndex,
    AddAssociatedKeyFuncIndex,
    RemoveAssociatedKeyFuncIndex,
    UpdateAssociatedKeyFuncIndex,
    SetActionThresholdFuncIndex,
    LoadNamedKeysFuncIndex,
    RemoveKeyFuncIndex,
    GetCallerIndex,
    GetBlocktimeIndex,
    CreatePurseIndex,
    TransferToAccountIndex,
    TransferFromPurseToAccountIndex,
    TransferFromPurseToPurseIndex,
    GetBalanceIndex,
    GetPhaseIndex,
    UpgradeContractAtURefIndex,
    GetSystemContractIndex,
    GetMainPurseIndex,
    GetArgSizeFuncIndex,
    ReadHostBufferIndex,
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
