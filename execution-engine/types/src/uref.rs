use alloc::{format, string::String, vec::Vec};
use core::{
    convert::TryFrom,
    fmt::{self, Debug, Display, Formatter},
};

use hex_fmt::HexFmt;

use crate::{
    bytesrepr::{self, OPTION_TAG_SERIALIZED_LENGTH, U32_SERIALIZED_LENGTH},
    AccessRights, ApiError, Key, ACCESS_RIGHTS_SERIALIZED_LENGTH,
};

/// The number of bytes in a [`URef`] address.
pub const UREF_ADDR_LENGTH: usize = 32;

/// The number of bytes in a serialized [`URef`] where the [`AccessRights`] are not `None`.
pub const UREF_SERIALIZED_LENGTH: usize =
    UREF_ADDR_LENGTH + OPTION_TAG_SERIALIZED_LENGTH + ACCESS_RIGHTS_SERIALIZED_LENGTH;

/// Represents an unforgeable reference, containing an address in the network's global storage and
/// the [`AccessRights`] of the reference.
///
/// A `URef` can be used to index entities such as [`CLValue`](crate::CLValue)s, or smart contracts.
#[derive(Copy, Clone, Hash, PartialEq, Eq, PartialOrd, Ord)]
pub struct URef([u8; UREF_ADDR_LENGTH], Option<AccessRights>);

impl URef {
    /// Constructs a [`URef`] from an address and access rights.
    pub fn new(address: [u8; UREF_ADDR_LENGTH], access_rights: AccessRights) -> Self {
        URef(address, Some(access_rights))
    }

    /// Constructs a [`URef`] from an address and optional access rights.  [`URef::new`] is the
    /// preferred constructor for most common use-cases.
    #[cfg(any(test, feature = "gens"))]
    pub(crate) fn unsafe_new(
        id: [u8; UREF_ADDR_LENGTH],
        maybe_access_rights: Option<AccessRights>,
    ) -> Self {
        URef(id, maybe_access_rights)
    }

    /// Returns the address of this [`URef`].
    pub fn addr(&self) -> [u8; UREF_ADDR_LENGTH] {
        self.0
    }

    /// Returns the access rights of this [`URef`].
    pub fn access_rights(&self) -> Option<AccessRights> {
        self.1
    }

    /// Returns a new [`URef`] with the same address and updated access rights.
    pub fn with_access_rights(self, access_rights: AccessRights) -> Self {
        URef(self.0, Some(access_rights))
    }

    /// Removes the access rights from this [`URef`].
    pub fn remove_access_rights(self) -> Self {
        URef(self.0, None)
    }

    /// Returns `true` if the access rights are `Some` and
    /// [`is_readable`](AccessRights::is_readable) is `true` for them.
    pub fn is_readable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_readable()
        } else {
            false
        }
    }

    /// Returns a new [`URef`] with the same address and [`AccessRights::READ`] permission.
    pub fn into_read(self) -> URef {
        URef(self.0, Some(AccessRights::READ))
    }

    /// Returns a new [`URef`] with the same address and [`AccessRights::READ_ADD_WRITE`]
    /// permission.
    pub fn into_read_add_write(self) -> URef {
        URef(self.0, Some(AccessRights::READ_ADD_WRITE))
    }

    /// Returns `true` if the access rights are `Some` and
    /// [`is_writeable`](AccessRights::is_writeable) is `true` for them.
    pub fn is_writeable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_writeable()
        } else {
            false
        }
    }

    /// Returns `true` if the access rights are `Some` and [`is_addable`](AccessRights::is_addable)
    /// is `true` for them.
    pub fn is_addable(self) -> bool {
        if let Some(access_rights) = self.1 {
            access_rights.is_addable()
        } else {
            false
        }
    }

    /// Formats the address and access rights of the [`URef`] in an unique way that could be used as
    /// a name when storing the given `URef` in a global state.
    pub fn as_string(&self) -> String {
        // Extract bits as numerical value, with no flags marked as 0.
        let access_rights_bits = self
            .access_rights()
            .map(|value| value.bits())
            .unwrap_or_default();
        // Access rights is represented as octal, which means that max value of u8 can
        // be represented as maximum of 3 octal digits.
        format!(
            "uref-{}-{:03o}",
            base16::encode_lower(&self.addr()),
            access_rights_bits
        )
    }
}

impl Display for URef {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        let addr = self.addr();
        let access_rights_o = self.access_rights();
        if let Some(access_rights) = access_rights_o {
            write!(f, "URef({}, {})", HexFmt(&addr), access_rights)
        } else {
            write!(f, "URef({}, None)", HexFmt(&addr))
        }
    }
}

impl Debug for URef {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "{}", self)
    }
}

impl bytesrepr::ToBytes for URef {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = Vec::with_capacity(UREF_SERIALIZED_LENGTH);
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

impl bytesrepr::FromBytes for Vec<URef> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (size, mut stream): (u32, &[u8]) = bytesrepr::FromBytes::from_bytes(bytes)?;
        let mut result = Vec::new();
        result.try_reserve_exact(size as usize)?;
        for _ in 0..size {
            let (uref, rem): (URef, &[u8]) = bytesrepr::FromBytes::from_bytes(stream)?;
            result.push(uref);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl bytesrepr::ToBytes for Vec<URef> {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SERIALIZED_LENGTH);
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(bytesrepr::ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}

impl TryFrom<Key> for URef {
    type Error = ApiError;

    fn try_from(key: Key) -> Result<Self, Self::Error> {
        if let Key::URef(uref) = key {
            Ok(uref)
        } else {
            Err(ApiError::UnexpectedKeyVariant)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uref_as_string() {
        // Since we are putting URefs to named_keys map keyed by the label that
        // `as_string()` returns, any changes to the string representation of
        // that type cannot break the format.
        let addr_array = [0u8; 32];
        let uref_a = URef::new(addr_array, AccessRights::READ);
        assert_eq!(
            uref_a.as_string(),
            "uref-0000000000000000000000000000000000000000000000000000000000000000-001"
        );
        let uref_b = URef::new(addr_array, AccessRights::WRITE);
        assert_eq!(
            uref_b.as_string(),
            "uref-0000000000000000000000000000000000000000000000000000000000000000-002"
        );

        let uref_c = uref_b.remove_access_rights();
        assert_eq!(
            uref_c.as_string(),
            "uref-0000000000000000000000000000000000000000000000000000000000000000-000"
        );
    }
}
