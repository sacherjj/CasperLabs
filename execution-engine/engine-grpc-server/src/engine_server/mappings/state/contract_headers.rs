use std::convert::TryFrom;
use types::{ContractHeader, ContractMetadata};

use crate::engine_server::{mappings::ParsingError, state};

impl From<ContractMetadata> for state::ContractMetadata {
    fn from(_value: ContractMetadata) -> state::ContractMetadata {
        todo!("from state contract metadata")
    }
}

impl TryFrom<state::ContractMetadata> for ContractMetadata {
    type Error = ParsingError;
    fn try_from(_value: state::ContractMetadata) -> Result<ContractMetadata, Self::Error> {
        todo!("into state contract metadata")
    }
}

impl From<ContractHeader> for state::ContractHeader {
    fn from(_value: ContractHeader) -> Self {
        todo!("from state contract header")
    }
}

impl TryFrom<state::ContractHeader> for ContractHeader {
    type Error = ParsingError;
    fn try_from(_value: state::ContractHeader) -> Result<ContractHeader, Self::Error> {
        todo!("into state contract header")
    }
}
