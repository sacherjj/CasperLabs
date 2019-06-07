use num_traits::{FromPrimitive, ToPrimitive};
use std::convert::TryFrom;

#[derive(Debug, PartialEq, FromPrimitive, ToPrimitive)]
#[repr(usize)]
pub enum FunctionIndex {
    WriteFuncIndex = 0,
    ReadFuncIndex = 1,
    AddFuncIndex = 2,
    NewFuncIndex = 3,
    GetReadFuncIndex = 4,
    SerFnFuncIndex = 5,
    GetFnFuncIndex = 6,
    LoadArgFuncIndex = 7,
    GetArgFuncIndex = 8,
    RetFuncIndex = 9,
    GetCallResultFuncIndex = 10,
    CallContractFuncIndex = 11,
    GetURefFuncIndex = 12,
    GasFuncIndex = 13,
    HasURefFuncIndex = 14,
    AddURefFuncIndex = 15,
    StoreFnIndex = 16,
    ProtocolVersionFuncIndex = 17,
    SeedFnIndex = 18,
    IsValidFnIndex = 19,
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
        FromPrimitive::from_usize(value).ok_or_else(|| "Invalid function index")
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
