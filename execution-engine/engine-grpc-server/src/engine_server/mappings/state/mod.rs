//! Functions for converting between CasperLabs types and their Protobuf equivalents which are
//! defined in protobuf/io/casperlabs/casper/consensus/state.proto

mod account;
mod big_int;
mod cl_type;
mod cl_value;
mod contract;
mod key;
mod named_key;
mod protocol_version;
mod stored_value;
mod uref;
mod value;

pub(crate) use named_key::NamedKeyMap;
