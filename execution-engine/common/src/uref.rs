use bitflags;

use crate::alloc::vec::Vec;
use crate::bytesrepr;
use crate::bytesrepr::U32_SIZE;

const UREF_ID_SIZE: usize = 32;
const ACCESS_RIGHTS_SIZE: usize = 1;
pub const UREF_SIZE_SERIALIZED: usize = U32_SIZE + UREF_ID_SIZE + U32_SIZE + ACCESS_RIGHTS_SIZE;

bitflags! {
    #[allow(clippy::derive_hash_xor_eq)]
    pub struct AccessRights: u8 {
        const READ  = 0b001;
        const WRITE = 0b010;
        const ADD   = 0b100;
        const READ_ADD       = Self::READ.bits | Self::ADD.bits;
        const READ_WRITE     = Self::READ.bits | Self::WRITE.bits;
        const ADD_WRITE      = Self::ADD.bits  | Self::WRITE.bits;
        const READ_ADD_WRITE = Self::READ.bits | Self::ADD.bits | Self::WRITE.bits;
    }
}

impl AccessRights {
    pub fn is_readable(self) -> bool {
        self & AccessRights::READ == AccessRights::READ
    }

    pub fn is_writeable(self) -> bool {
        self & AccessRights::WRITE == AccessRights::WRITE
    }

    pub fn is_addable(self) -> bool {
        self & AccessRights::ADD == AccessRights::ADD
    }
}

impl bytesrepr::ToBytes for AccessRights {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        self.bits.to_bytes()
    }
}

impl bytesrepr::FromBytes for AccessRights {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (id, rem): (u8, &[u8]) = bytesrepr::FromBytes::from_bytes(bytes)?;
        match AccessRights::from_bits(id) {
            Some(rights) => Ok((rights, rem)),
            None => Err(bytesrepr::Error::FormattingError),
        }
    }
}

/// Represents an unforgeable reference
#[derive(Debug, Copy, Clone, Hash, PartialEq, Eq, PartialOrd, Ord)]
pub struct URef([u8; UREF_ID_SIZE], Option<AccessRights>);

impl URef {
    /// Creates a [`URef`] from an id and access rights.
    pub fn new(id: [u8; UREF_ID_SIZE], access_rights: AccessRights) -> Self {
        URef(id, Some(access_rights))
    }

    /// Creates a [`URef`] from an id and optional access rights.  [`URef::new`] is the
    /// preferred constructor for most common use-cases.
    pub fn unsafe_new(id: [u8; UREF_ID_SIZE], maybe_access_rights: Option<AccessRights>) -> Self {
        URef(id, maybe_access_rights)
    }

    /// Returns the id of this URef.
    pub fn id(&self) -> [u8; UREF_ID_SIZE] {
        self.0
    }

    /// Returns the access rights of this URef.
    pub fn access_rights(&self) -> Option<AccessRights> {
        self.1
    }

    /// Removes the access rights from this URef.
    pub fn remove_access_rights(self) -> Self {
        URef(self.0, None)
    }

    pub fn is_readable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_readable()
        } else {
            false
        }
    }

    pub fn is_writeable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_writeable()
        } else {
            false
        }
    }

    pub fn is_addable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_addable()
        } else {
            false
        }
    }
}

impl bytesrepr::ToBytes for URef {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = Vec::with_capacity(UREF_SIZE_SERIALIZED);
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        Ok(result)
    }
}

impl bytesrepr::FromBytes for URef {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (id, rem): ([u8; 32], &[u8]) = bytesrepr::FromBytes::from_bytes(bytes)?;
        let (maybe_access_rights, rem): (Option<AccessRights>, &[u8]) =
            bytesrepr::FromBytes::from_bytes(rem)?;
        Ok((URef(id, maybe_access_rights), rem))
    }
}

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod tests {
    use proptest::proptest;

    use crate::gens;
    use crate::test_utils;
    use crate::uref::AccessRights;

    fn test_readable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_readable(), is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(AccessRights::READ, true);
        test_readable(AccessRights::READ_ADD, true);
        test_readable(AccessRights::READ_WRITE, true);
        test_readable(AccessRights::READ_ADD_WRITE, true);
        test_readable(AccessRights::ADD, false);
        test_readable(AccessRights::ADD_WRITE, false);
        test_readable(AccessRights::WRITE, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_writeable(), is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(AccessRights::WRITE, true);
        test_writable(AccessRights::READ_WRITE, true);
        test_writable(AccessRights::ADD_WRITE, true);
        test_writable(AccessRights::READ, false);
        test_writable(AccessRights::ADD, false);
        test_writable(AccessRights::READ_ADD, false);
        test_writable(AccessRights::READ_ADD_WRITE, true);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_addable(), is_true)
    }

    #[test]
    fn test_is_addable() {
        test_addable(AccessRights::ADD, true);
        test_addable(AccessRights::READ_ADD, true);
        test_addable(AccessRights::READ_WRITE, false);
        test_addable(AccessRights::ADD_WRITE, true);
        test_addable(AccessRights::READ, false);
        test_addable(AccessRights::WRITE, false);
        test_addable(AccessRights::READ_ADD_WRITE, true);
    }

    proptest! {
        #[test]
        fn test_uref(uref in gens::uref_arb()) {
            assert!(test_utils::test_serialization_roundtrip(&uref));
        }
    }
}
