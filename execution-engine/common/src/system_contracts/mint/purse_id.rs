use crate::uref::AccessRights;
use alloc::fmt;

#[derive(Debug, Copy, Clone)]
pub enum PurseIdError {
    InvalidURef,
    InvalidAccessRights(Option<AccessRights>),
}

impl fmt::Display for PurseIdError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            PurseIdError::InvalidURef => write!(f, "invalid uref"),
            PurseIdError::InvalidAccessRights(maybe_access_rights) => {
                write!(f, "invalid access rights: {:?}", maybe_access_rights)
            }
        }
    }
}
