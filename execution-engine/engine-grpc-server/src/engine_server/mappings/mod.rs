use std::{
    collections::{BTreeMap, BTreeSet},
    convert::{TryFrom, TryInto},
    fmt::{self, Display, Formatter},
    string::ToString,
};

use protobuf::{ProtobufEnum, RepeatedField};

use contract_ffi::{
    uref::URef,
    value::{
        account::{ActionThresholds, AssociatedKeys, PublicKey, PurseId, Weight, KEY_SIZE},
        ProtocolVersion, U512,
    },
};
use engine_core::{
    engine_state::{
        self,
        deploy_item::DeployItem,
        executable_deploy_item::ExecutableDeployItem,
        execution_effect::ExecutionEffect,
        execution_result::ExecutionResult,
        genesis::{GenesisAccount, GenesisConfig},
        op::Op,
        upgrade::UpgradeConfig,
        Error as EngineError, RootNotFound,
    },
    execution::Error as ExecutionError,
    DEPLOY_HASH_LENGTH,
};
use engine_shared::{
    additive_map::AdditiveMap,
    motes::Motes,
    transform::{self, TypeMismatch},
};
use engine_wasm_prep::wasm_costs::WasmCosts;

use crate::engine_server::{ipc, state, transforms};

mod uint;

/// Helper method for turning instances of Value into Transform::Write.
fn transform_write(v: contract_ffi::value::Value) -> Result<transform::Transform, ParsingError> {
    Ok(transform::Transform::Write(v))
}

#[derive(Debug)]
pub enum MappingError {
    InvalidHashLength { expected: usize, actual: usize },
    InvalidPublicKeyLength { expected: usize, actual: usize },
    InvalidDeployHashLength { expected: usize, actual: usize },
    ParsingError(ParsingError),
    InvalidStateHash(String),
    MissingPayload,
}

impl MappingError {
    pub fn invalid_public_key_length(actual: usize) -> Self {
        let expected = KEY_SIZE;
        MappingError::InvalidPublicKeyLength { expected, actual }
    }

    pub fn invalid_deploy_hash_length(actual: usize) -> Self {
        let expected = DEPLOY_HASH_LENGTH;
        MappingError::InvalidDeployHashLength { expected, actual }
    }
}

impl From<ParsingError> for MappingError {
    fn from(error: ParsingError) -> Self {
        MappingError::ParsingError(error)
    }
}

// This is whackadoodle, we know
impl From<MappingError> for engine_state::Error {
    fn from(error: MappingError) -> Self {
        match error {
            MappingError::InvalidHashLength { expected, actual } => {
                engine_state::Error::InvalidHashLength { expected, actual }
            }
            _ => engine_state::Error::DeployError,
        }
    }
}

impl Display for MappingError {
    fn fmt(&self, f: &mut Formatter) -> Result<(), fmt::Error> {
        match self {
            MappingError::InvalidHashLength { expected, actual } => write!(
                f,
                "Invalid hash length: expected {}, actual {}",
                expected, actual
            ),
            MappingError::InvalidPublicKeyLength { expected, actual } => write!(
                f,
                "Invalid public key length: expected {}, actual {}",
                expected, actual
            ),
            MappingError::InvalidDeployHashLength { expected, actual } => write!(
                f,
                "Invalid deploy hash length: expected {}, actual {}",
                expected, actual
            ),
            MappingError::ParsingError(ParsingError(message)) => {
                write!(f, "Parsing error: {}", message)
            }
            MappingError::InvalidStateHash(message) => write!(f, "Invalid hash: {}", message),
            MappingError::MissingPayload => write!(f, "Missing payload"),
        }
    }
}

#[derive(Debug)]
pub struct ParsingError(pub String);

impl ParsingError {
    /// Creates custom error given any type that implements Display.
    ///
    /// This includes types derived from Fail (for example) so it enables
    /// short syntax for functions returning Result<_, ParsingError>:
    ///
    ///   any_func().map_err(ParsingError::custom)
    fn custom<T: Display>(value: T) -> ParsingError {
        ParsingError(value.to_string())
    }
}

/// Smart constructor for parse errors
fn parse_error<T>(message: String) -> Result<T, ParsingError> {
    Err(ParsingError(message))
}

impl TryFrom<&super::state::Key_URef> for URef {
    type Error = ParsingError;

    fn try_from(ipc_uref: &super::state::Key_URef) -> Result<Self, Self::Error> {
        let addr = {
            let source = &ipc_uref.uref;
            if source.len() != 32 {
                return parse_error("URef key has to be 32 bytes long.".to_string());
            }
            let mut ret = [0u8; 32];
            ret.copy_from_slice(source);
            ret
        };
        let uref = {
            let access_rights_value: i32 = ipc_uref.access_rights.value();
            if access_rights_value != 0 {
                let access_rights_bits = access_rights_value.try_into().unwrap();
                let access_rights =
                    contract_ffi::uref::AccessRights::from_bits(access_rights_bits).unwrap();
                URef::new(addr, access_rights)
            } else {
                URef::new(addr, contract_ffi::uref::AccessRights::READ).remove_access_rights()
            }
        };
        Ok(uref)
    }
}

impl From<URef> for super::state::Key_URef {
    fn from(uref: URef) -> Self {
        let mut key_uref = super::state::Key_URef::new();
        key_uref.set_uref(uref.addr().to_vec());
        if let Some(access_rights) = uref.access_rights() {
            key_uref.set_access_rights(
                state::Key_URef_AccessRights::from_i32(access_rights.bits().into()).unwrap(),
            );
        }
        key_uref
    }
}

impl TryFrom<&super::transforms::Transform> for transform::Transform {
    type Error = ParsingError;
    fn try_from(tr: &super::transforms::Transform) -> Result<transform::Transform, ParsingError> {
        if tr.has_identity() {
            Ok(transform::Transform::Identity)
        } else if tr.has_add_keys() {
            let keys_map = tr
                .get_add_keys()
                .get_value()
                .iter()
                .map(|nk| {
                    let local_nk = nk.clone();
                    local_nk.get_key().try_into().map(|k| (local_nk.name, k))
                })
                .collect::<Result<BTreeMap<String, contract_ffi::key::Key>, ParsingError>>()?;
            Ok(transform::Transform::AddKeys(keys_map))
        } else if tr.has_add_i32() {
            Ok(transform::Transform::AddInt32(tr.get_add_i32().value))
        } else if tr.has_add_u64() {
            Ok(transform::Transform::AddUInt64(tr.get_add_u64().value))
        } else if tr.has_add_big_int() {
            let b = tr.get_add_big_int().get_value();
            let v = b.try_into()?;
            match v {
                contract_ffi::value::Value::UInt128(u) => Ok(u.into()),
                contract_ffi::value::Value::UInt256(u) => Ok(u.into()),
                contract_ffi::value::Value::UInt512(u) => Ok(u.into()),
                other => parse_error(format!("Through some impossibility a BigInt was turned into a non-uint value type: ${:?}", other))
            }
        } else if tr.has_write() {
            let v = tr.get_write().get_value();
            transform_write(v.try_into()?)
        } else {
            parse_error("TransformEntry couldn't be parsed to known Transform.".to_owned())
        }
    }
}

impl From<contract_ffi::value::Contract> for super::state::Contract {
    fn from(contract: contract_ffi::value::Contract) -> Self {
        let (bytes, named_keys, protocol_version) = contract.destructure();
        let mut contract = super::state::Contract::new();
        let named_keys = KnownKeys(named_keys).into();
        contract.set_body(bytes);
        contract.set_named_keys(protobuf::RepeatedField::from_vec(named_keys));
        contract.set_protocol_version(protocol_version.into());
        contract
    }
}

impl TryFrom<&super::state::Contract> for contract_ffi::value::Contract {
    type Error = ParsingError;

    fn try_from(value: &super::state::Contract) -> Result<Self, Self::Error> {
        let input = value.get_protocol_version();
        let protocol_version =
            ProtocolVersion::from_parts(input.get_major(), input.get_minor(), input.get_patch());
        let named_keys: KnownKeys = value.get_named_keys().try_into()?;
        Ok(contract_ffi::value::Contract::new(
            value.get_body().to_vec(),
            named_keys.0,
            protocol_version,
        ))
    }
}

impl From<contract_ffi::value::Value> for super::state::Value {
    fn from(v: contract_ffi::value::Value) -> Self {
        let mut tv = super::state::Value::new();
        match v {
            contract_ffi::value::Value::Int32(i) => {
                tv.set_int_value(i);
            }
            contract_ffi::value::Value::UInt128(u) => tv.set_big_int(u.into()),
            contract_ffi::value::Value::UInt256(u) => tv.set_big_int(u.into()),
            contract_ffi::value::Value::UInt512(u) => tv.set_big_int(u.into()),
            contract_ffi::value::Value::ByteArray(arr) => {
                tv.set_bytes_value(arr);
            }
            contract_ffi::value::Value::ListInt32(list) => {
                let mut int_list = super::state::IntList::new();
                int_list.set_values(list);
                tv.set_int_list(int_list);
            }
            contract_ffi::value::Value::String(string) => {
                tv.set_string_value(string);
            }
            contract_ffi::value::Value::ListString(list_string) => {
                let mut string_list = super::state::StringList::new();
                string_list.set_values(protobuf::RepeatedField::from_ref(list_string));
                tv.set_string_list(string_list);
            }
            contract_ffi::value::Value::NamedKey(name, key) => {
                let named_key = {
                    let mut nk = super::state::NamedKey::new();
                    nk.set_name(name.to_string());
                    nk.set_key(key.into());
                    nk
                };
                tv.set_named_key(named_key);
            }
            contract_ffi::value::Value::Key(key) => {
                tv.set_key(key.into());
            }
            contract_ffi::value::Value::Account(account) => tv.set_account(account.into()),
            contract_ffi::value::Value::Contract(contract) => {
                tv.set_contract(contract.into());
            }
            contract_ffi::value::Value::Unit => tv.set_unit(state::Unit::new()),
            contract_ffi::value::Value::UInt64(num) => tv.set_long_value(num),
        };
        tv
    }
}

impl TryFrom<&super::state::Value> for contract_ffi::value::Value {
    type Error = ParsingError;

    fn try_from(value: &super::state::Value) -> Result<Self, Self::Error> {
        if value.has_int_value() {
            Ok(contract_ffi::value::Value::Int32(value.get_int_value()))
        } else if value.has_bytes_value() {
            Ok(contract_ffi::value::Value::ByteArray(
                value.get_bytes_value().to_vec(),
            ))
        } else if value.has_int_list() {
            Ok(contract_ffi::value::Value::ListInt32(
                value.get_int_list().get_values().to_vec(),
            ))
        } else if value.has_string_value() {
            Ok(contract_ffi::value::Value::String(
                value.get_string_value().to_string(),
            ))
        } else if value.has_account() {
            Ok(contract_ffi::value::Value::Account(
                value.get_account().try_into()?,
            ))
        } else if value.has_contract() {
            let contract: contract_ffi::value::Contract = value.get_contract().try_into()?;
            Ok(contract_ffi::value::Value::Contract(contract))
        } else if value.has_string_list() {
            Ok(contract_ffi::value::Value::ListString(
                value.get_string_list().get_values().to_vec(),
            ))
        } else if value.has_named_key() {
            let (name, key) = value.get_named_key().try_into()?;
            Ok(contract_ffi::value::Value::NamedKey(name, key))
        } else if value.has_big_int() {
            Ok(value.get_big_int().try_into()?)
        } else if value.has_key() {
            Ok(contract_ffi::value::Value::Key(value.get_key().try_into()?))
        } else if value.has_unit() {
            Ok(contract_ffi::value::Value::Unit)
        } else if value.has_long_value() {
            Ok(contract_ffi::value::Value::UInt64(value.get_long_value()))
        } else {
            parse_error(format!(
                "IPC Value {:?} couldn't be parsed to domain representation.",
                value
            ))
        }
    }
}

impl From<contract_ffi::value::account::Account> for super::state::Account {
    fn from(account: contract_ffi::value::account::Account) -> Self {
        let mut ipc_account = super::state::Account::new();
        ipc_account.set_public_key(account.pub_key().to_vec());
        ipc_account.set_purse_id(account.purse_id().value().into());
        let associated_keys: Vec<super::state::Account_AssociatedKey> = account
            .get_associated_keys()
            .map(|(key, weight)| {
                let mut ipc_associated_key = super::state::Account_AssociatedKey::new();
                ipc_associated_key.set_public_key(key.value().to_vec());
                ipc_associated_key.set_weight(u32::from(weight.value()));
                ipc_associated_key
            })
            .collect();
        let action_thresholds = {
            let mut tmp = state::Account_ActionThresholds::new();
            tmp.set_key_management_threshold(u32::from(
                account.action_thresholds().key_management().value(),
            ));
            tmp.set_deployment_threshold(u32::from(
                account.action_thresholds().deployment().value(),
            ));
            tmp
        };
        ipc_account.set_action_thresholds(action_thresholds);
        let account_named_keys = KnownKeys(account.named_keys().to_owned());
        let ipc_urefs: Vec<super::state::NamedKey> = account_named_keys.into();
        ipc_account.set_named_keys(ipc_urefs.into());
        ipc_account.set_associated_keys(associated_keys.into());
        ipc_account
    }
}

impl TryFrom<&super::state::Account> for contract_ffi::value::account::Account {
    type Error = ParsingError;

    fn try_from(value: &super::state::Account) -> Result<Self, Self::Error> {
        let pub_key: [u8; 32] = {
            if value.public_key.len() != 32 {
                return parse_error("Public key has to be exactly 32 bytes long.".to_string());
            }
            let mut buff = [0u8; 32];
            buff.copy_from_slice(&value.public_key);
            buff
        };
        let named_keys: KnownKeys = value.get_named_keys().try_into()?;
        let purse_id: PurseId = PurseId::new(value.get_purse_id().try_into()?);
        let associated_keys: AssociatedKeys = {
            let mut keys = AssociatedKeys::empty();
            value.get_associated_keys().iter().try_for_each(|k| {
                let (pub_key, weight) = k.try_into()?;
                match keys.add_key(pub_key, weight) {
                    Err(add_key_failure) => parse_error(format!(
                        "Error when parsing associated keys: {:?}",
                        add_key_failure
                    )),
                    Ok(_) => Ok(()),
                }
            })?;
            keys
        };
        let action_thresholds: ActionThresholds = {
            if !value.has_action_thresholds() {
                return parse_error(
                    "Missing ActionThresholds object of the Account IPC message.".to_string(),
                );
            };
            let action_thresholds_ipc = value.get_action_thresholds();

            ActionThresholds::new(
                Weight::new(action_thresholds_ipc.get_deployment_threshold() as u8),
                Weight::new(action_thresholds_ipc.get_key_management_threshold() as u8),
            )
            .map_err(ParsingError::custom)?
        };

        Ok(contract_ffi::value::Account::new(
            pub_key,
            named_keys.0,
            purse_id,
            associated_keys,
            action_thresholds,
        ))
    }
}

fn add_big_int_transform<U: Into<super::state::BigInt>>(
    t: &mut super::transforms::Transform,
    u: U,
) {
    let mut add = super::transforms::TransformAddBigInt::new();
    add.set_value(u.into());
    t.set_add_big_int(add);
}

impl From<transform::Transform> for super::transforms::Transform {
    fn from(tr: transform::Transform) -> Self {
        let mut t = super::transforms::Transform::new();
        match tr {
            transform::Transform::Identity => {
                t.set_identity(super::transforms::TransformIdentity::new());
            }
            transform::Transform::Write(v) => {
                let mut tw = super::transforms::TransformWrite::new();
                tw.set_value(v.into());
                t.set_write(tw)
            }
            transform::Transform::AddInt32(i) => {
                let mut add = super::transforms::TransformAddInt32::new();
                add.set_value(i);
                t.set_add_i32(add);
            }
            transform::Transform::AddUInt64(i) => {
                let mut add = super::transforms::TransformAddUInt64::new();
                add.set_value(i);
                t.set_add_u64(add);
            }
            transform::Transform::AddUInt128(u) => {
                add_big_int_transform(&mut t, u);
            }
            transform::Transform::AddUInt256(u) => {
                add_big_int_transform(&mut t, u);
            }
            transform::Transform::AddUInt512(u) => {
                add_big_int_transform(&mut t, u);
            }
            transform::Transform::AddKeys(keys_map) => {
                let mut add = super::transforms::TransformAddKeys::new();
                let keys = KnownKeys(keys_map).into();
                add.set_value(protobuf::RepeatedField::from_vec(keys));
                t.set_add_keys(add);
            }
            transform::Transform::Failure(transform::Error::TypeMismatch(
                transform::TypeMismatch { expected, found },
            )) => {
                let mut fail = super::transforms::TransformFailure::new();
                let mut typemismatch_err = super::transforms::TypeMismatch::new();
                typemismatch_err.set_expected(expected.to_owned());
                typemismatch_err.set_found(found.to_owned());
                fail.set_type_mismatch(typemismatch_err);
                t.set_failure(fail);
            }
        };
        t
    }
}

// newtype because trait impl have to be defined in the crate of the type.
pub struct KnownKeys(BTreeMap<String, contract_ffi::key::Key>);

impl TryFrom<&super::state::NamedKey> for (String, contract_ffi::key::Key) {
    type Error = ParsingError;

    fn try_from(value: &super::state::NamedKey) -> Result<Self, Self::Error> {
        let name = value.get_name().to_string();
        let key = value.get_key().try_into()?;
        Ok((name, key))
    }
}

// Helper method for turning gRPC Vec of NamedKey to domain BTreeMap.
impl TryFrom<&[super::state::NamedKey]> for KnownKeys {
    type Error = ParsingError;
    fn try_from(from: &[super::state::NamedKey]) -> Result<Self, ParsingError> {
        let mut tree: BTreeMap<String, contract_ffi::key::Key> = BTreeMap::new();
        for nk in from {
            let (name, key) = nk.try_into()?;
            let _ = tree.insert(name, key);
        }
        Ok(KnownKeys(tree))
    }
}

impl From<KnownKeys> for Vec<super::state::NamedKey> {
    fn from(uref_map: KnownKeys) -> Vec<super::state::NamedKey> {
        uref_map
            .0
            .into_iter()
            .map(|(n, k)| {
                let mut nk = super::state::NamedKey::new();
                nk.set_name(n);
                nk.set_key(k.into());
                nk
            })
            .collect()
    }
}

impl TryFrom<&state::Account_AssociatedKey> for (PublicKey, Weight) {
    type Error = ParsingError;

    fn try_from(value: &state::Account_AssociatedKey) -> Result<Self, Self::Error> {
        // Weight is a newtype wrapper around u8 type.
        if value.get_weight() > u8::max_value().into() {
            parse_error("Key weight cannot be bigger 256.".to_string())
        } else {
            let source = &value.get_public_key();
            if source.len() != 32 {
                return parse_error("Public key has to be exactly 32 bytes long.".to_string());
            }
            let mut pub_key = [0u8; 32];
            pub_key.copy_from_slice(source);
            Ok((
                PublicKey::new(pub_key),
                Weight::new(value.get_weight() as u8),
            ))
        }
    }
}

impl From<contract_ffi::key::Key> for super::state::Key {
    fn from(key: contract_ffi::key::Key) -> super::state::Key {
        let mut k = super::state::Key::new();
        match key {
            contract_ffi::key::Key::Account(acc) => {
                let mut key_addr = super::state::Key_Address::new();
                key_addr.set_account(acc.to_vec());
                k.set_address(key_addr);
            }
            contract_ffi::key::Key::Hash(hash) => {
                let mut key_hash = super::state::Key_Hash::new();
                key_hash.set_hash(hash.to_vec());
                k.set_hash(key_hash);
            }
            contract_ffi::key::Key::URef(uref) => {
                let uref: super::state::Key_URef = uref.into();
                k.set_uref(uref);
            }
            contract_ffi::key::Key::Local(hash) => {
                let mut key_local = super::state::Key_Local::new();
                key_local.set_hash(hash.to_vec());
                k.set_local(key_local);
            }
        }
        k
    }
}

impl TryFrom<&super::state::Key> for contract_ffi::key::Key {
    type Error = ParsingError;

    fn try_from(ipc_key: &super::state::Key) -> Result<Self, ParsingError> {
        if ipc_key.has_address() {
            let arr = {
                let source = &ipc_key.get_address().account;
                if source.len() != 32 {
                    return parse_error("Account key has to be 32 bytes long.".to_string());
                }
                let mut dest = [0u8; 32];
                dest.copy_from_slice(source);
                dest
            };
            Ok(contract_ffi::key::Key::Account(arr))
        } else if ipc_key.has_hash() {
            let arr = {
                let source = &ipc_key.get_hash().hash;
                if source.len() != 32 {
                    return parse_error("Hash key has to be 32 bytes long.".to_string());
                }
                let mut dest = [0u8; 32];
                dest.copy_from_slice(source);
                dest
            };
            Ok(contract_ffi::key::Key::Hash(arr))
        } else if ipc_key.has_uref() {
            let uref = ipc_key.get_uref().try_into()?;
            Ok(contract_ffi::key::Key::URef(uref))
        } else if ipc_key.has_local() {
            let ipc_local_key = ipc_key.get_local();
            if ipc_local_key.hash.len() != 32 {
                parse_error("Hash of local key have to be 32 bytes long.".to_string())
            } else {
                let mut hash_buff = [0u8; 32];
                hash_buff.copy_from_slice(&ipc_local_key.hash);
                Ok(contract_ffi::key::Key::Local(hash_buff))
            }
        } else {
            parse_error(format!(
                "ipc Key couldn't be parsed to any Key: {:?}",
                ipc_key
            ))
        }
    }
}

impl From<Op> for super::ipc::Op {
    fn from(op: Op) -> super::ipc::Op {
        let mut ipc_op = super::ipc::Op::new();
        match op {
            Op::Read => ipc_op.set_read(super::ipc::ReadOp::new()),
            Op::Write => ipc_op.set_write(super::ipc::WriteOp::new()),
            Op::Add => ipc_op.set_add(super::ipc::AddOp::new()),
            Op::NoOp => ipc_op.set_noop(super::ipc::NoOp::new()),
        };
        ipc_op
    }
}

// Newtype wrapper as rustc requires because trait impl have to be defined in
// the crate of the type.
#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CommitTransforms(AdditiveMap<contract_ffi::key::Key, transform::Transform>);

impl CommitTransforms {
    pub fn get(&self, key: &contract_ffi::key::Key) -> Option<&transform::Transform> {
        self.0.get(&key)
    }

    pub fn value(self) -> AdditiveMap<contract_ffi::key::Key, transform::Transform> {
        self.0
    }
}

impl TryFrom<&[super::transforms::TransformEntry]> for CommitTransforms {
    type Error = ParsingError;

    fn try_from(value: &[super::transforms::TransformEntry]) -> Result<Self, Self::Error> {
        let mut transforms_merged: AdditiveMap<contract_ffi::key::Key, transform::Transform> =
            AdditiveMap::new();
        for named_key in value.iter() {
            let (key, transform): (contract_ffi::key::Key, transform::Transform) =
                named_key.try_into()?;
            transforms_merged.insert_add(key, transform);
        }
        Ok(CommitTransforms(transforms_merged))
    }
}

/// Transforms gRPC TransformEntry into domain tuple of (Key, Transform).
impl TryFrom<&super::transforms::TransformEntry>
    for (contract_ffi::key::Key, transform::Transform)
{
    type Error = ParsingError;
    fn try_from(from: &super::transforms::TransformEntry) -> Result<Self, ParsingError> {
        if from.has_key() {
            if from.has_transform() {
                let t: transform::Transform = from.get_transform().try_into()?;
                let key = from.get_key().try_into()?;
                Ok((key, t))
            } else {
                parse_error("No transform field in TransformEntry".to_owned())
            }
        } else {
            parse_error("No key field in TransformEntry".to_owned())
        }
    }
}

impl From<(contract_ffi::key::Key, transform::Transform)> for super::transforms::TransformEntry {
    fn from((k, t): (contract_ffi::key::Key, transform::Transform)) -> Self {
        let mut tr_entry = super::transforms::TransformEntry::new();
        tr_entry.set_key(k.into());
        tr_entry.set_transform(t.into());
        tr_entry
    }
}

impl From<ExecutionEffect> for super::ipc::ExecutionEffect {
    fn from(ee: ExecutionEffect) -> super::ipc::ExecutionEffect {
        let mut eff = super::ipc::ExecutionEffect::new();
        let ipc_ops: Vec<super::ipc::OpEntry> = ee
            .ops
            .into_iter()
            .map(|(k, o)| {
                let mut op_entry = super::ipc::OpEntry::new();
                let ipc_key = k.into();
                let ipc_op = o.clone().into();
                op_entry.set_key(ipc_key);
                op_entry.set_operation(ipc_op);
                op_entry
            })
            .collect();
        let ipc_tran: Vec<super::transforms::TransformEntry> =
            ee.transforms.into_iter().map(Into::into).collect();
        eff.set_op_map(protobuf::RepeatedField::from_vec(ipc_ops));
        eff.set_transform_map(protobuf::RepeatedField::from_vec(ipc_tran));
        eff
    }
}

impl From<RootNotFound> for ipc::RootNotFound {
    fn from(err: RootNotFound) -> ipc::RootNotFound {
        let RootNotFound(missing_root_hash) = err;
        let mut root_missing_err = ipc::RootNotFound::new();
        root_missing_err.set_hash(missing_root_hash.to_vec());
        root_missing_err
    }
}

impl From<TypeMismatch> for transforms::TypeMismatch {
    fn from(type_mismatch: TypeMismatch) -> transforms::TypeMismatch {
        let TypeMismatch { expected, found } = type_mismatch;
        let mut tm = transforms::TypeMismatch::new();
        tm.set_expected(expected);
        tm.set_found(found);
        tm
    }
}

impl From<ExecutionResult> for ipc::DeployResult {
    fn from(er: ExecutionResult) -> ipc::DeployResult {
        match er {
            ExecutionResult::Success {
                effect: effects,
                cost,
            } => {
                let ipc_ee = effects.into();
                let mut deploy_result = ipc::DeployResult::new();
                let mut execution_result = ipc::DeployResult_ExecutionResult::new();
                execution_result.set_effects(ipc_ee);
                execution_result.set_cost(cost.value().into());
                deploy_result.set_execution_result(execution_result);
                deploy_result
            }
            ExecutionResult::Failure {
                error: err,
                effect,
                cost,
            } => {
                match err {
                    // TODO(mateusz.gorski): Fix error model for the storage errors.
                    // We don't have separate IPC messages for storage errors
                    // so for the time being they are all reported as "wasm errors".
                    error @ EngineError::InvalidHashLength { .. } => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::InvalidPublicKeyLength { .. } => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::InvalidProtocolVersion { .. } => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::InvalidUpgradeConfig => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::WasmPreprocessingError(_) => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::WasmSerializationError(_) => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::ExecError(
                        ExecutionError::DeploymentAuthorizationFailure,
                    ) => precondition_failure(error.to_string()),
                    EngineError::StorageError(storage_err) => {
                        execution_error(storage_err.to_string(), cost.value(), effect)
                    }
                    error @ EngineError::AuthorizationError => {
                        precondition_failure(error.to_string())
                    }
                    EngineError::MissingSystemContractError(msg) => {
                        execution_error(msg, cost.value(), effect)
                    }
                    error @ EngineError::InsufficientPaymentError => {
                        let msg = error.to_string();
                        execution_error(msg, cost.value(), effect)
                    }
                    error @ EngineError::DeployError => {
                        let msg = error.to_string();
                        execution_error(msg, cost.value(), effect)
                    }
                    error @ EngineError::FinalizationError => {
                        let msg = error.to_string();
                        execution_error(msg, cost.value(), effect)
                    }
                    error @ EngineError::SerializationError(_) => {
                        let msg = error.to_string();
                        execution_error(msg, cost.value(), effect)
                    }
                    error @ EngineError::MintError(_) => {
                        let msg = error.to_string();
                        execution_error(msg, cost.value(), effect)
                    }
                    EngineError::ExecError(exec_error) => match exec_error {
                        ExecutionError::GasLimit => {
                            let mut deploy_result = ipc::DeployResult::new();
                            let deploy_error = {
                                let mut tmp = ipc::DeployError::new();
                                tmp.set_gas_error(ipc::DeployError_OutOfGasError::new());
                                tmp
                            };
                            let exec_result = {
                                let mut tmp = ipc::DeployResult_ExecutionResult::new();
                                tmp.set_error(deploy_error);
                                tmp.set_cost(cost.value().into());
                                tmp.set_effects(effect.into());
                                tmp
                            };

                            deploy_result.set_execution_result(exec_result);
                            deploy_result
                        }
                        ExecutionError::KeyNotFound(key) => {
                            let msg = format!("Key {:?} not found.", key);
                            execution_error(msg, cost.value(), effect)
                        }
                        ExecutionError::Revert(status) => {
                            let error_msg = format!("Exit code: {}", status);
                            execution_error(error_msg, cost.value(), effect)
                        }
                        ExecutionError::Interpreter(error) => {
                            // If the error happens during contract execution it's mapped to
                            // HostError and wrapped in Interpreter
                            // error, so we may end up with
                            // InterpreterError(HostError(InterpreterError))).
                            // In order to provide clear error messages we have to downcast and
                            // match on the inner error, otherwise we
                            // end up with `Host(Trap(Trap(TrapKind:InterpreterError)))`.
                            // TODO: This really should be happening in the `Executor::exec`.
                            match error.as_host_error() {
                                Some(host_error) => {
                                    let downcasted_error =
                                        host_error.downcast_ref::<ExecutionError>().unwrap();
                                    match downcasted_error {
                                        ExecutionError::Revert(status) => {
                                            let errors_msg = format!("Exit code: {}", status);
                                            execution_error(errors_msg, cost.value(), effect)
                                        }
                                        ExecutionError::KeyNotFound(key) => {
                                            let errors_msg = format!("Key {:?} not found.", key);
                                            execution_error(errors_msg, cost.value(), effect)
                                        }
                                        ExecutionError::InvalidContext => {
                                            // TODO: https://casperlabs.atlassian.net/browse/EE-771
                                            let errors_msg = "Invalid execution context.";
                                            execution_error(
                                                errors_msg.to_string(),
                                                cost.value(),
                                                effect,
                                            )
                                        }
                                        other => execution_error(
                                            format!("{:?}", other),
                                            cost.value(),
                                            effect,
                                        ),
                                    }
                                }

                                None => {
                                    let msg = format!("{:?}", error);
                                    execution_error(msg, cost.value(), effect)
                                }
                            }
                        }
                        // TODO(mateusz.gorski): Be more specific about execution errors
                        other => {
                            let msg = format!("{:?}", other);
                            execution_error(msg, cost.value(), effect)
                        }
                    },
                }
            }
        }
    }
}

impl From<ipc::DeployPayload_oneof_payload> for ExecutableDeployItem {
    fn from(deploy_payload: ipc::DeployPayload_oneof_payload) -> Self {
        match deploy_payload {
            ipc::DeployPayload_oneof_payload::deploy_code(deploy_code) => {
                ExecutableDeployItem::ModuleBytes {
                    module_bytes: deploy_code.code,
                    args: deploy_code.args,
                }
            }
            ipc::DeployPayload_oneof_payload::stored_contract_hash(stored_contract_hash) => {
                ExecutableDeployItem::StoredContractByHash {
                    hash: stored_contract_hash.hash,
                    args: stored_contract_hash.args,
                }
            }
            ipc::DeployPayload_oneof_payload::stored_contract_name(stored_contract_name) => {
                ExecutableDeployItem::StoredContractByName {
                    name: stored_contract_name.stored_contract_name,
                    args: stored_contract_name.args,
                }
            }
            ipc::DeployPayload_oneof_payload::stored_contract_uref(stored_contract_uref) => {
                ExecutableDeployItem::StoredContractByURef {
                    uref: stored_contract_uref.uref,
                    args: stored_contract_uref.args,
                }
            }
        }
    }
}

impl TryFrom<ipc::ChainSpec_GenesisAccount> for GenesisAccount {
    type Error = MappingError;

    fn try_from(genesis_account: ipc::ChainSpec_GenesisAccount) -> Result<Self, Self::Error> {
        let public_key = {
            let tmp = genesis_account.get_public_key();
            // TODO: our TryFromSliceForPublicKeyError should convey length info
            match tmp.try_into() {
                Ok(public_key) => public_key,
                Err(_) => return Err(MappingError::invalid_public_key_length(tmp.len())),
            }
        };
        let balance = genesis_account.get_balance().try_into().map(Motes::new)?;
        let bonded_amount = genesis_account
            .get_bonded_amount()
            .try_into()
            .map(Motes::new)?;
        Ok(GenesisAccount::new(public_key, balance, bonded_amount))
    }
}

impl From<GenesisAccount> for ipc::ChainSpec_GenesisAccount {
    fn from(account: GenesisAccount) -> Self {
        let mut ret = ipc::ChainSpec_GenesisAccount::new();

        ret.set_public_key(account.public_key().value().to_vec());

        let mut bonded_amount = state::BigInt::new();
        bonded_amount.set_bit_width(512);
        bonded_amount.set_value(account.bonded_amount().to_string());
        ret.set_bonded_amount(bonded_amount);

        let mut balance = state::BigInt::new();
        balance.set_bit_width(512);
        balance.set_value(account.balance().to_string());
        ret.set_balance(balance);
        ret
    }
}

impl From<ipc::ChainSpec_CostTable_WasmCosts> for WasmCosts {
    fn from(wasm_costs: ipc::ChainSpec_CostTable_WasmCosts) -> Self {
        let regular = wasm_costs.get_regular();
        let div = wasm_costs.get_div();
        let mul = wasm_costs.get_mul();
        let mem = wasm_costs.get_mem();
        let initial_mem = wasm_costs.get_initial_mem();
        let grow_mem = wasm_costs.get_grow_mem();
        let memcpy = wasm_costs.get_memcpy();
        let max_stack_height = wasm_costs.get_max_stack_height();
        let opcodes_mul = wasm_costs.get_opcodes_mul();
        let opcodes_div = wasm_costs.get_opcodes_div();
        WasmCosts {
            regular,
            div,
            mul,
            mem,
            initial_mem,
            grow_mem,
            memcpy,
            max_stack_height,
            opcodes_mul,
            opcodes_div,
        }
    }
}

impl From<WasmCosts> for ipc::ChainSpec_CostTable_WasmCosts {
    fn from(wasm_costs: WasmCosts) -> Self {
        let mut cost_table = ipc::ChainSpec_CostTable_WasmCosts::new();
        cost_table.set_regular(wasm_costs.regular);
        cost_table.set_div(wasm_costs.div);
        cost_table.set_mul(wasm_costs.mul);
        cost_table.set_mem(wasm_costs.mem);
        cost_table.set_initial_mem(wasm_costs.initial_mem);
        cost_table.set_grow_mem(wasm_costs.grow_mem);
        cost_table.set_memcpy(wasm_costs.memcpy);
        cost_table.set_max_stack_height(wasm_costs.max_stack_height);
        cost_table.set_opcodes_mul(wasm_costs.opcodes_mul);
        cost_table.set_opcodes_div(wasm_costs.opcodes_div);
        cost_table
    }
}

impl TryFrom<ipc::ChainSpec_GenesisConfig> for GenesisConfig {
    type Error = MappingError;

    fn try_from(genesis_config: ipc::ChainSpec_GenesisConfig) -> Result<Self, Self::Error> {
        let name = genesis_config.get_name().to_string();
        let timestamp = genesis_config.get_timestamp();
        let protocol_version = genesis_config.get_protocol_version();
        let mint_initializer_bytes = genesis_config.get_mint_installer().to_vec();
        let proof_of_stake_initializer_bytes = genesis_config.get_pos_installer().to_vec();
        let accounts = genesis_config
            .get_accounts()
            .iter()
            .cloned()
            .map(TryInto::try_into)
            .collect::<Result<Vec<GenesisAccount>, Self::Error>>()?;
        let wasm_costs = genesis_config.get_costs().get_wasm().to_owned().into();
        Ok(GenesisConfig::new(
            name,
            timestamp,
            ProtocolVersion::from_parts(
                protocol_version.get_major(),
                protocol_version.get_minor(),
                protocol_version.get_patch(),
            ),
            mint_initializer_bytes,
            proof_of_stake_initializer_bytes,
            accounts,
            wasm_costs,
        ))
    }
}

impl From<GenesisConfig> for ipc::ChainSpec_GenesisConfig {
    fn from(genesis_config: GenesisConfig) -> Self {
        let mut ret = ipc::ChainSpec_GenesisConfig::new();
        ret.set_name(genesis_config.name().to_string());
        ret.set_timestamp(genesis_config.timestamp());
        {
            ret.set_protocol_version(genesis_config.protocol_version().into());
        }
        ret.set_mint_installer(genesis_config.mint_installer_bytes().to_vec());
        ret.set_pos_installer(genesis_config.proof_of_stake_installer_bytes().to_vec());
        {
            let accounts = genesis_config
                .accounts()
                .iter()
                .cloned()
                .map(Into::into)
                .collect::<Vec<ipc::ChainSpec_GenesisAccount>>();
            ret.set_accounts(RepeatedField::from(accounts));
        }
        {
            let mut cost_table = ipc::ChainSpec_CostTable::new();
            cost_table.set_wasm(genesis_config.wasm_costs().into());
            ret.set_costs(cost_table);
        }
        ret
    }
}

impl TryFrom<ipc::UpgradeRequest> for UpgradeConfig {
    type Error = MappingError;

    fn try_from(upgrade_request: ipc::UpgradeRequest) -> Result<Self, Self::Error> {
        let pre_state_hash = upgrade_request
            .get_parent_state_hash()
            .try_into()
            .map_err(|_| MappingError::InvalidStateHash("pre_state_hash".to_string()))?;

        let current_protocol_version = upgrade_request.get_protocol_version().into();

        let upgrade_point = upgrade_request.get_upgrade_point();
        let new_protocol_version: ProtocolVersion = upgrade_point.get_protocol_version().into();
        let (upgrade_installer_bytes, upgrade_installer_args) =
            if !upgrade_point.has_upgrade_installer() {
                (None, None)
            } else {
                let upgrade_installer = upgrade_point.get_upgrade_installer();
                let bytes = upgrade_installer.get_code().to_vec();
                let bytes = if bytes.is_empty() { None } else { Some(bytes) };
                let args = upgrade_installer.get_args().to_vec();
                let args = if args.is_empty() { None } else { Some(args) };
                (bytes, args)
            };

        let wasm_costs = if !upgrade_point.has_new_costs() {
            None
        } else {
            Some(upgrade_point.get_new_costs().get_wasm().to_owned().into())
        };
        let activation_point = if !upgrade_point.has_activation_point() {
            None
        } else {
            Some(upgrade_point.get_activation_point().rank)
        };

        Ok(UpgradeConfig::new(
            pre_state_hash,
            current_protocol_version,
            new_protocol_version,
            upgrade_installer_args,
            upgrade_installer_bytes,
            wasm_costs,
            activation_point,
        ))
    }
}

impl From<&state::ProtocolVersion> for contract_ffi::value::ProtocolVersion {
    fn from(protocol_version: &state::ProtocolVersion) -> Self {
        contract_ffi::value::ProtocolVersion::from_parts(
            protocol_version.major,
            protocol_version.minor,
            protocol_version.patch,
        )
    }
}

impl From<contract_ffi::value::ProtocolVersion> for state::ProtocolVersion {
    fn from(protocol_version: ProtocolVersion) -> Self {
        let sem_ver = protocol_version.value();
        let mut protocol = super::state::ProtocolVersion::new();
        protocol.set_major(sem_ver.major);
        protocol.set_minor(sem_ver.minor);
        protocol.set_patch(sem_ver.patch);
        protocol
    }
}

/// Constructs an instance of [[ipc::DeployResult]] with an error set to
/// [[ipc::DeployError_PreconditionFailure]].
fn precondition_failure(msg: String) -> ipc::DeployResult {
    let mut deploy_result = ipc::DeployResult::new();
    let mut precondition_failure = ipc::DeployResult_PreconditionFailure::new();
    precondition_failure.set_message(msg);
    deploy_result.set_precondition_failure(precondition_failure);
    deploy_result
}

/// Constructs an instance of [[ipc::DeployResult]] with error set to
/// [[ipc::DeployError_ExecutionError]].
fn execution_error(msg: String, cost: U512, effect: ExecutionEffect) -> ipc::DeployResult {
    let mut deploy_result = ipc::DeployResult::new();
    let deploy_error = {
        let mut tmp = ipc::DeployError::new();
        let mut err = ipc::DeployError_ExecutionError::new();
        err.set_message(msg.to_owned());
        tmp.set_exec_error(err);
        tmp
    };
    let execution_result = {
        let mut tmp = ipc::DeployResult_ExecutionResult::new();
        tmp.set_error(deploy_error);
        tmp.set_cost(cost.into());
        tmp.set_effects(effect.into());
        tmp
    };
    deploy_result.set_execution_result(execution_result);
    deploy_result
}

impl From<(PublicKey, U512)> for ipc::Bond {
    fn from(tuple: (PublicKey, U512)) -> Self {
        let (key, amount) = tuple;
        let mut ret = ipc::Bond::new();
        ret.set_validator_public_key(key.to_vec());
        ret.set_stake(amount.into());
        ret
    }
}

impl TryFrom<&ipc::Bond> for (PublicKey, U512) {
    type Error = MappingError;

    fn try_from(bond: &ipc::Bond) -> Result<Self, Self::Error> {
        let public_key = {
            let tmp = bond.get_validator_public_key();
            // TODO: our TryFromSliceForPublicKeyError should convey length info
            match tmp.try_into() {
                Ok(public_key) => public_key,
                Err(_) => return Err(MappingError::invalid_public_key_length(tmp.len())),
            }
        };
        let stake = bond.get_stake().try_into()?;
        Ok((public_key, stake))
    }
}

impl TryFrom<&ipc::DeployItem> for DeployItem {
    type Error = MappingError;

    fn try_from(value: &ipc::DeployItem) -> Result<Self, Self::Error> {
        let address = {
            let tmp = value.get_address();
            match tmp.try_into() {
                Ok(public_key) => public_key,
                Err(_) => return Err(MappingError::invalid_public_key_length(tmp.len())),
            }
        };
        let session = match &(value.get_session()).payload {
            Some(payload) => payload.to_owned().into(),
            None => return Err(MappingError::MissingPayload),
        };
        let payment = match &(value.get_payment()).payload {
            Some(payload) => payload.to_owned().into(),
            None => return Err(MappingError::MissingPayload),
        };
        let gas_price = value.get_gas_price();
        let authorization_keys = value
            .get_authorization_keys()
            .iter()
            .map(|raw: &Vec<u8>| {
                raw.as_slice()
                    .try_into()
                    .map_err(|_| MappingError::invalid_public_key_length(raw.len()))
            })
            .collect::<Result<BTreeSet<PublicKey>, Self::Error>>()?;
        let deploy_hash = {
            let source = value.get_deploy_hash();
            let length = source.len();
            if length != DEPLOY_HASH_LENGTH {
                return Err(MappingError::invalid_deploy_hash_length(length));
            }
            let mut ret = [0u8; DEPLOY_HASH_LENGTH];
            ret.copy_from_slice(source);
            ret
        };

        Ok(DeployItem::new(
            address,
            session,
            payment,
            gas_price,
            authorization_keys,
            deploy_hash,
        ))
    }
}

#[cfg(test)]
mod tests {
    use std::convert::TryInto;

    use proptest::prelude::*;

    use contract_ffi::{
        gens::{account_arb, contract_arb, key_arb, named_keys_arb, value_arb},
        key::Key,
        uref::{AccessRights, URef},
    };
    use engine_core::{
        engine_state::{
            execution_effect::ExecutionEffect, execution_result::ExecutionResult,
            Error as EngineError, RootNotFound,
        },
        execution::Error,
    };
    use engine_shared::{
        additive_map::AdditiveMap,
        gas::Gas,
        newtypes::Blake2bHash,
        transform::{gens::transform_arb, Transform},
    };

    use crate::engine_server::mappings::CommitTransforms;

    use super::{execution_error, ipc, state, transforms};
    use contract_ffi::value::U512;

    // Test that wasm_error function actually returns DeployResult with result set
    // to WasmError
    #[test]
    fn wasm_error_result() {
        let error_msg = "ExecError";
        let mut result = execution_error(error_msg.to_owned(), U512::zero(), Default::default());
        assert!(result.has_execution_result());
        let mut exec_result = result.take_execution_result();
        assert!(exec_result.has_error());
        let mut ipc_error = exec_result.take_error();
        assert!(ipc_error.has_exec_error());
        let ipc_exec_error = ipc_error.take_exec_error();
        let ipc_error_msg = ipc_exec_error.get_message();
        assert_eq!(ipc_error_msg, error_msg);
    }

    #[test]
    fn deploy_result_to_ipc_missing_root() {
        let root_hash: Blake2bHash = [1u8; 32].into();
        let mut result: ipc::RootNotFound = RootNotFound(root_hash).into();
        let ipc_missing_hash = result.take_hash();
        assert_eq!(root_hash.to_vec(), ipc_missing_hash);
    }

    #[test]
    fn deploy_result_to_ipc_success() {
        let input_transforms: AdditiveMap<Key, Transform> = {
            let mut tmp_map = AdditiveMap::new();
            tmp_map.insert(
                Key::URef(URef::new([1u8; 32], AccessRights::ADD)),
                Transform::AddInt32(10),
            );
            tmp_map
        };
        let execution_effect: ExecutionEffect =
            ExecutionEffect::new(AdditiveMap::new(), input_transforms.clone());
        let cost: Gas = Gas::new(U512::from(123));
        let execution_result: ExecutionResult = ExecutionResult::Success {
            effect: execution_effect,
            cost,
        };
        let mut ipc_deploy_result: ipc::DeployResult = execution_result.into();
        assert!(ipc_deploy_result.has_execution_result());
        let mut success = ipc_deploy_result.take_execution_result();
        let execution_cost: U512 = success.get_cost().try_into().expect("should map to U512");
        assert_eq!(execution_cost, cost.value());

        // Extract transform map from the IPC message and parse it back to the domain
        let ipc_transforms: AdditiveMap<Key, Transform> = {
            let mut ipc_effects = success.take_effects();
            let ipc_effects_tnfs = ipc_effects.take_transform_map().into_vec();
            ipc_effects_tnfs
                .iter()
                .map(|e| e.try_into())
                .collect::<Result<AdditiveMap<Key, Transform>, _>>()
                .unwrap()
        };
        assert_eq!(&input_transforms, &ipc_transforms);
    }

    fn into_execution_failure<E: Into<EngineError>>(error: E, cost: Gas) -> ExecutionResult {
        ExecutionResult::Failure {
            error: error.into(),
            effect: Default::default(),
            cost,
        }
    }

    fn test_cost<E: Into<EngineError>>(expected_cost: Gas, err: E) -> Gas {
        let execution_failure = into_execution_failure(err, expected_cost);
        let ipc_deploy_result: ipc::DeployResult = execution_failure.into();
        assert!(ipc_deploy_result.has_execution_result());
        let success = ipc_deploy_result.get_execution_result();
        let execution_cost: U512 = success.get_cost().try_into().expect("should map to U512");
        Gas::new(execution_cost)
    }

    #[test]
    fn storage_error_has_cost() {
        use engine_storage::error::Error::*;
        let cost: Gas = Gas::new(U512::from(100));
        // TODO: actually create an Rkv error
        // assert_eq!(test_cost(cost, RkvError("Error".to_owned())), cost);
        let bytesrepr_err = contract_ffi::bytesrepr::Error::EarlyEndOfStream;
        assert_eq!(test_cost(cost, BytesRepr(bytesrepr_err)), cost);
    }

    #[test]
    fn exec_err_has_cost() {
        let cost: Gas = Gas::new(U512::from(100));
        // GasLimit error is treated differently at the moment so test separately
        assert_eq!(
            test_cost(cost, engine_core::execution::Error::GasLimit),
            cost
        );
        // for the time being all other execution errors are treated in the same way
        let forged_ref_error = engine_core::execution::Error::ForgedReference(URef::new(
            [1u8; 32],
            AccessRights::READ_ADD_WRITE,
        ));
        assert_eq!(test_cost(cost, forged_ref_error), cost);
    }

    #[test]
    fn commit_effects_merges_transforms() {
        // Tests that transforms made to the same key are merged instead of lost.
        let key = Key::Hash([1u8; 32]);
        let setup: Vec<transforms::TransformEntry> = {
            let transform_entry_first = {
                let mut tmp = transforms::TransformEntry::new();
                tmp.set_key(key.into());
                tmp.set_transform(Transform::Write(contract_ffi::value::Value::Int32(12)).into());
                tmp
            };
            let transform_entry_second = {
                let mut tmp = transforms::TransformEntry::new();
                tmp.set_key(key.into());
                tmp.set_transform(Transform::AddInt32(10).into());
                tmp
            };
            vec![transform_entry_first, transform_entry_second]
        };
        let setup_slice: &[transforms::TransformEntry] = &setup;
        let commit: CommitTransforms = setup_slice
            .try_into()
            .expect("Transforming [state::TransformEntry] into CommitTransforms should work.");
        let expected_transform = Transform::Write(contract_ffi::value::Value::Int32(22i32));
        let commit_transform = commit.get(&key);
        assert!(commit_transform.is_some());
        assert_eq!(expected_transform, *commit_transform.unwrap())
    }

    #[test]
    fn revert_error_maps_to_execution_error() {
        const REVERT: u32 = 10;
        let revert_error = Error::Revert(REVERT);
        let amount: U512 = U512::from(15);
        let exec_result = ExecutionResult::Failure {
            error: EngineError::ExecError(revert_error),
            effect: Default::default(),
            cost: Gas::new(amount),
        };
        let ipc_result: ipc::DeployResult = exec_result.into();
        assert!(
            ipc_result.has_execution_result(),
            "should have execution result"
        );
        let ipc_execution_result = ipc_result.get_execution_result();
        let execution_cost: U512 = ipc_execution_result
            .get_cost()
            .try_into()
            .expect("should map to U512");
        assert_eq!(execution_cost, amount, "execution cost should equal amount");
        assert_eq!(
            ipc_execution_result
                .get_error()
                .get_exec_error()
                .get_message(),
            format!("Exit code: {}", REVERT)
        );
    }

    proptest! {
        #[test]
        fn key_roundtrip(key in key_arb()) {
            let ipc_key: super::state::Key = key.into();
            let key_back: Key = (&ipc_key).try_into().expect("Transforming state::Key into domain Key should succeed.");
            assert_eq!(key_back, key)
        }

        #[test]
        fn named_keys_roundtrip(named_keys in named_keys_arb(10)) {
            let named_keys_newtype = super::KnownKeys(named_keys.clone());
            let ipc_named_keys: Vec<state::NamedKey> = named_keys_newtype.into();
            let ipc_urefs_slice: &[state::NamedKey] = &ipc_named_keys;
            let named_keys_back: super::KnownKeys = ipc_urefs_slice.try_into()
                .expect("Transforming Vec<state::NamedKey> to KnownKeys should succeed.");
            assert_eq!(named_keys, named_keys_back.0)
        }

        #[test]
        fn account_roundtrip(account in account_arb()) {
            let ipc_account: super::state::Account = account.clone().into();
            let account_back = (&ipc_account).try_into()
                .expect("Transforming state::Account into domain Account should succeed.");
            assert_eq!(account, account_back)
        }

        #[test]
        fn contract_roundtrip(contract in contract_arb()) {
            let ipc_contract: super::state::Contract = contract.clone().into();
            let contract_back = (&ipc_contract).try_into()
                .expect("Transforming state::Contract into domain Contract should succeed.");
            assert_eq!(contract, contract_back)
        }

        #[test]
        fn value_roundtrip(value in value_arb()) {
            let ipc_value: super::state::Value = value.clone().into();
            let value_back = (&ipc_value).try_into()
                .expect("Transforming state::Value into domain Value should succeed.");
            assert_eq!(value, value_back)
        }

        #[test]
        fn transform_entry_roundtrip(key in key_arb(), transform in transform_arb()) {
            let transform_entry: transforms::TransformEntry = (key, transform.clone()).into();
            let tuple: (Key, Transform) = (&transform_entry).try_into()
                .expect("Transforming TransformEntry into (Key, Transform) tuple should work.");
            assert_eq!(tuple, (key, transform))
        }

    }
}
