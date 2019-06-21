use num_traits::{FromPrimitive, ToPrimitive};
use std::convert::TryFrom;

#[derive(Debug, PartialEq, FromPrimitive, ToPrimitive)]
#[repr(usize)]
pub enum FunctionIndex {
    WriteFuncIndex = 0,
    WriteLocalFuncIndex = 1,
    ReadFuncIndex = 2,
    ReadLocalFuncIndex = 3,
    AddFuncIndex = 4,
    NewFuncIndex = 5,
    GetReadFuncIndex = 6,
    SerFnFuncIndex = 7,
    GetFnFuncIndex = 8,
    LoadArgFuncIndex = 9,
    GetArgFuncIndex = 10,
    RetFuncIndex = 11,
    GetCallResultFuncIndex = 12,
    CallContractFuncIndex = 13,
    GetURefFuncIndex = 14,
    GasFuncIndex = 15,
    HasURefFuncIndex = 16,
    AddURefFuncIndex = 17,
    StoreFnIndex = 18,
    ProtocolVersionFuncIndex = 19,
    IsValidFnIndex = 20,
    RevertFuncIndex = 21,
    AddAssociatedKeyFuncIndex = 22,
    RemoveAssociatedKeyFuncIndex = 23,
    SetActionThresholdFuncIndex = 24,
    SerKnownURefs = 25,
    ListKnownURefsIndex = 26,
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
        let element = FunctionIndex::try_from(20).expect("Unable to create enum from number");
        assert_eq!(element, FunctionIndex::IsValidFnIndex);
    }
    #[test]
    fn enum_to_primitive() {
        let element = FunctionIndex::IsValidFnIndex;
        let primitive: usize = element.into();
        assert_eq!(primitive, 20usize);
    }
    #[test]
    #[should_panic]
    fn invalid_index() {
        FunctionIndex::try_from(123_456_789usize).unwrap();
    }
}
