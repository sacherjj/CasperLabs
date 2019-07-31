use std::collections::{BTreeMap, HashMap};
use std::convert::{TryFrom, TryInto};
use std::fmt::Display;
use std::string::ToString;

use protobuf::ProtobufEnum;

use common::uref::URef;
use common::value::account::{
    AccountActivity, ActionThresholds, AssociatedKeys, BlockTime, PublicKey, PurseId, Weight,
};
use common::value::U512;
use engine_server::{ipc, state};
use execution_engine::engine_state::error::{Error as EngineError, RootNotFound};
use execution_engine::engine_state::execution_effect::ExecutionEffect;
use execution_engine::engine_state::execution_result::ExecutionResult;
use execution_engine::engine_state::op::Op;
use execution_engine::execution::Error as ExecutionError;
use execution_engine::utils;
use shared::logging;
use shared::logging::log_level;
use shared::newtypes::Blake2bHash;
use shared::transform::{self, TypeMismatch};
use storage::global_state::{CommitResult, History};

mod uint;

/// Helper method for turning instances of Value into Transform::Write.
fn transform_write(v: common::value::Value) -> Result<transform::Transform, ParsingError> {
    Ok(transform::Transform::Write(v))
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
                    common::uref::AccessRights::from_bits(access_rights_bits).unwrap();
                URef::new(addr, access_rights)
            } else {
                URef::new(addr, common::uref::AccessRights::READ).remove_access_rights()
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

impl TryFrom<&super::ipc::Transform> for transform::Transform {
    type Error = ParsingError;
    fn try_from(tr: &super::ipc::Transform) -> Result<transform::Transform, ParsingError> {
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
                .collect::<Result<BTreeMap<String, common::key::Key>, ParsingError>>()?;
            Ok(transform::Transform::AddKeys(keys_map))
        } else if tr.has_add_i32() {
            Ok(transform::Transform::AddInt32(tr.get_add_i32().value))
        } else if tr.has_add_u64() {
            Ok(transform::Transform::AddUInt64(tr.get_add_u64().value))
        } else if tr.has_add_big_int() {
            let b = tr.get_add_big_int().get_value();
            let v = b.try_into()?;
            match v {
                common::value::Value::UInt128(u) => Ok(u.into()),
                common::value::Value::UInt256(u) => Ok(u.into()),
                common::value::Value::UInt512(u) => Ok(u.into()),
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

impl From<common::value::Contract> for super::state::Contract {
    fn from(contract: common::value::Contract) -> Self {
        let (bytes, known_urefs, protocol_version) = contract.destructure();
        let mut contract = super::state::Contract::new();
        let urefs = URefMap(known_urefs).into();
        contract.set_body(bytes);
        contract.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
        let mut protocol = super::state::ProtocolVersion::new();
        protocol.set_value(protocol_version);
        contract.set_protocol_version(protocol);
        contract
    }
}

impl TryFrom<&super::state::Contract> for common::value::Contract {
    type Error = ParsingError;

    fn try_from(value: &super::state::Contract) -> Result<Self, Self::Error> {
        let known_urefs: URefMap = value.get_known_urefs().try_into()?;
        Ok(common::value::Contract::new(
            value.get_body().to_vec(),
            known_urefs.0,
            value.get_protocol_version().value,
        ))
    }
}

impl From<common::value::Value> for super::state::Value {
    fn from(v: common::value::Value) -> Self {
        let mut tv = super::state::Value::new();
        match v {
            common::value::Value::Int32(i) => {
                tv.set_int_value(i);
            }
            common::value::Value::UInt128(u) => tv.set_big_int(u.into()),
            common::value::Value::UInt256(u) => tv.set_big_int(u.into()),
            common::value::Value::UInt512(u) => tv.set_big_int(u.into()),
            common::value::Value::ByteArray(arr) => {
                tv.set_bytes_value(arr);
            }
            common::value::Value::ListInt32(list) => {
                let mut int_list = super::state::IntList::new();
                int_list.set_values(list);
                tv.set_int_list(int_list);
            }
            common::value::Value::String(string) => {
                tv.set_string_value(string);
            }
            common::value::Value::ListString(list_string) => {
                let mut string_list = super::state::StringList::new();
                string_list.set_values(protobuf::RepeatedField::from_ref(list_string));
                tv.set_string_list(string_list);
            }
            common::value::Value::NamedKey(name, key) => {
                let named_key = {
                    let mut nk = super::state::NamedKey::new();
                    nk.set_name(name.to_string());
                    nk.set_key((&key).into());
                    nk
                };
                tv.set_named_key(named_key);
            }
            common::value::Value::Key(key) => {
                tv.set_key((&key).into());
            }
            common::value::Value::Account(account) => tv.set_account(account.into()),
            common::value::Value::Contract(contract) => {
                tv.set_contract(contract.into());
            }
            common::value::Value::Unit => tv.set_unit(state::Unit::new()),
            common::value::Value::UInt64(num) => tv.set_long_value(num),
        };
        tv
    }
}

impl TryFrom<&super::state::Value> for common::value::Value {
    type Error = ParsingError;

    fn try_from(value: &super::state::Value) -> Result<Self, Self::Error> {
        if value.has_int_value() {
            Ok(common::value::Value::Int32(value.get_int_value()))
        } else if value.has_bytes_value() {
            Ok(common::value::Value::ByteArray(
                value.get_bytes_value().to_vec(),
            ))
        } else if value.has_int_list() {
            Ok(common::value::Value::ListInt32(
                value.get_int_list().get_values().to_vec(),
            ))
        } else if value.has_string_value() {
            Ok(common::value::Value::String(
                value.get_string_value().to_string(),
            ))
        } else if value.has_account() {
            Ok(common::value::Value::Account(
                value.get_account().try_into()?,
            ))
        } else if value.has_contract() {
            let contract: common::value::Contract = value.get_contract().try_into()?;
            Ok(common::value::Value::Contract(contract))
        } else if value.has_string_list() {
            Ok(common::value::Value::ListString(
                value.get_string_list().get_values().to_vec(),
            ))
        } else if value.has_named_key() {
            let (name, key) = value.get_named_key().try_into()?;
            Ok(common::value::Value::NamedKey(name, key))
        } else if value.has_big_int() {
            Ok(value.get_big_int().try_into()?)
        } else if value.has_key() {
            Ok(common::value::Value::Key(value.get_key().try_into()?))
        } else if value.has_unit() {
            Ok(common::value::Value::Unit)
        } else if value.has_long_value() {
            Ok(common::value::Value::UInt64(value.get_long_value()))
        } else {
            parse_error(format!(
                "IPC Value {:?} couldn't be parsed to domain representation.",
                value
            ))
        }
    }
}

impl From<common::value::account::Account> for super::state::Account {
    fn from(account: common::value::account::Account) -> Self {
        let mut ipc_account = super::state::Account::new();
        ipc_account.set_public_key(account.pub_key().to_vec());
        ipc_account.set_nonce(account.nonce());
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
        let account_activity = {
            let mut tmp = state::Account_AccountActivity::new();
            tmp.set_deployment_last_used(account.account_activity().deployment_last_used().0);
            tmp.set_key_management_last_used(
                account.account_activity().key_management_last_used().0,
            );
            tmp.set_inactivity_period_limit(account.account_activity().inactivity_period_limit().0);
            tmp
        };
        let account_urefs = account.urefs_lookup();
        let account_urefs_lookup = URefMap(account_urefs.clone());
        let ipc_urefs: Vec<super::state::NamedKey> = account_urefs_lookup.into();
        ipc_account.set_known_urefs(ipc_urefs.into());
        ipc_account.set_associated_keys(associated_keys.into());
        ipc_account.set_account_activity(account_activity);
        ipc_account
    }
}

impl TryFrom<&super::state::Account> for common::value::account::Account {
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
        let uref_map: URefMap = value.get_known_urefs().try_into()?;
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
        let account_activity: AccountActivity = {
            if !value.has_account_activity() {
                return parse_error(
                    "Missing AccountActivity object of the Account IPC message.".to_string(),
                );
            };
            let account_activity_ipc = value.get_account_activity();
            let mut tmp = AccountActivity::new(BlockTime(0), BlockTime(0));
            tmp.update_deployment_last_used(BlockTime(account_activity_ipc.deployment_last_used));
            tmp.update_key_management_last_used(BlockTime(
                account_activity_ipc.key_management_last_used,
            ));
            tmp.update_inactivity_period_limit(BlockTime(
                account_activity_ipc.inactivity_period_limit,
            ));
            tmp
        };
        Ok(common::value::Account::new(
            pub_key,
            value.nonce,
            uref_map.0,
            purse_id,
            associated_keys,
            action_thresholds,
            account_activity,
        ))
    }
}

fn add_big_int_transform<U: Into<super::state::BigInt>>(t: &mut super::ipc::Transform, u: U) {
    let mut add = super::ipc::TransformAddBigInt::new();
    add.set_value(u.into());
    t.set_add_big_int(add);
}

impl From<transform::Transform> for super::ipc::Transform {
    fn from(tr: transform::Transform) -> Self {
        let mut t = super::ipc::Transform::new();
        match tr {
            transform::Transform::Identity => {
                t.set_identity(super::ipc::TransformIdentity::new());
            }
            transform::Transform::Write(v) => {
                let mut tw = super::ipc::TransformWrite::new();
                tw.set_value(v.into());
                t.set_write(tw)
            }
            transform::Transform::AddInt32(i) => {
                let mut add = super::ipc::TransformAddInt32::new();
                add.set_value(i);
                t.set_add_i32(add);
            }
            transform::Transform::AddUInt64(i) => {
                let mut add = super::ipc::TransformAddUInt64::new();
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
                let mut add = super::ipc::TransformAddKeys::new();
                let keys = URefMap(keys_map).into();
                add.set_value(protobuf::RepeatedField::from_vec(keys));
                t.set_add_keys(add);
            }
            transform::Transform::Failure(transform::Error::TypeMismatch(
                transform::TypeMismatch { expected, found },
            )) => {
                let mut fail = super::ipc::TransformFailure::new();
                let mut typemismatch_err = super::ipc::TypeMismatch::new();
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
pub struct URefMap(BTreeMap<String, common::key::Key>);

impl TryFrom<&super::state::NamedKey> for (String, common::key::Key) {
    type Error = ParsingError;

    fn try_from(value: &super::state::NamedKey) -> Result<Self, Self::Error> {
        let name = value.get_name().to_string();
        let key = value.get_key().try_into()?;
        Ok((name, key))
    }
}

// Helper method for turning gRPC Vec of NamedKey to domain BTreeMap.
impl TryFrom<&[super::state::NamedKey]> for URefMap {
    type Error = ParsingError;
    fn try_from(from: &[super::state::NamedKey]) -> Result<Self, ParsingError> {
        let mut tree: BTreeMap<String, common::key::Key> = BTreeMap::new();
        for nk in from {
            let (name, key) = nk.try_into()?;
            let _ = tree.insert(name, key);
        }
        Ok(URefMap(tree))
    }
}

impl From<URefMap> for Vec<super::state::NamedKey> {
    fn from(uref_map: URefMap) -> Vec<super::state::NamedKey> {
        uref_map
            .0
            .into_iter()
            .map(|(n, k)| {
                let mut nk = super::state::NamedKey::new();
                nk.set_name(n);
                nk.set_key((&k).into());
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

impl From<&common::key::Key> for super::state::Key {
    fn from(key: &common::key::Key) -> super::state::Key {
        let mut k = super::state::Key::new();
        match key {
            common::key::Key::Account(acc) => {
                let mut key_addr = super::state::Key_Address::new();
                key_addr.set_account(acc.to_vec());
                k.set_address(key_addr);
            }
            common::key::Key::Hash(hash) => {
                let mut key_hash = super::state::Key_Hash::new();
                key_hash.set_hash(hash.to_vec());
                k.set_hash(key_hash);
            }
            common::key::Key::URef(uref) => {
                let uref: super::state::Key_URef = (*uref).into();
                k.set_uref(uref);
            }
            common::key::Key::Local(hash) => {
                let mut key_local = super::state::Key_Local::new();
                key_local.set_hash(hash.to_vec());
                k.set_local(key_local);
            }
        }
        k
    }
}

impl TryFrom<&super::state::Key> for common::key::Key {
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
            Ok(common::key::Key::Account(arr))
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
            Ok(common::key::Key::Hash(arr))
        } else if ipc_key.has_uref() {
            let uref = ipc_key.get_uref().try_into()?;
            Ok(common::key::Key::URef(uref))
        } else if ipc_key.has_local() {
            let ipc_local_key = ipc_key.get_local();
            if ipc_local_key.hash.len() != 32 {
                parse_error("Hash of local key have to be 32 bytes long.".to_string())
            } else {
                let mut hash_buff = [0u8; 32];
                hash_buff.copy_from_slice(&ipc_local_key.hash);
                Ok(common::key::Key::Local(hash_buff))
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

// Newtype wrapper as rustc requires because trait impl have to be defined in the crate of the type.
#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CommitTransforms(HashMap<common::key::Key, transform::Transform>);

impl CommitTransforms {
    pub fn get(&self, key: &common::key::Key) -> Option<&transform::Transform> {
        self.0.get(&key)
    }

    pub fn value(self) -> HashMap<common::key::Key, transform::Transform> {
        self.0
    }
}

impl TryFrom<&[super::ipc::TransformEntry]> for CommitTransforms {
    type Error = ParsingError;

    fn try_from(value: &[super::ipc::TransformEntry]) -> Result<Self, Self::Error> {
        let mut transforms_merged: HashMap<common::key::Key, transform::Transform> = HashMap::new();
        for named_key in value.iter() {
            let (key, transform): (common::key::Key, transform::Transform) =
                named_key.try_into()?;
            utils::add(&mut transforms_merged, key, transform);
        }
        Ok(CommitTransforms(transforms_merged))
    }
}

/// Transforms gRPC TransformEntry into domain tuple of (Key, Transform).
impl TryFrom<&super::ipc::TransformEntry> for (common::key::Key, transform::Transform) {
    type Error = ParsingError;
    fn try_from(from: &super::ipc::TransformEntry) -> Result<Self, ParsingError> {
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

impl From<(common::key::Key, transform::Transform)> for super::ipc::TransformEntry {
    fn from((k, t): (common::key::Key, transform::Transform)) -> Self {
        let mut tr_entry = super::ipc::TransformEntry::new();
        tr_entry.set_key((&k).into());
        tr_entry.set_transform(t.into());
        tr_entry
    }
}

impl From<ExecutionEffect> for super::ipc::ExecutionEffect {
    fn from(ee: ExecutionEffect) -> super::ipc::ExecutionEffect {
        let mut eff = super::ipc::ExecutionEffect::new();
        let ipc_ops: Vec<super::ipc::OpEntry> = ee
            .ops
            .iter()
            .map(|(k, o)| {
                let mut op_entry = super::ipc::OpEntry::new();
                let ipc_key = k.into();
                let ipc_op = o.clone().into();
                op_entry.set_key(ipc_key);
                op_entry.set_operation(ipc_op);
                op_entry
            })
            .collect();
        let ipc_tran: Vec<super::ipc::TransformEntry> =
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

impl From<TypeMismatch> for ipc::TypeMismatch {
    fn from(type_mismatch: TypeMismatch) -> ipc::TypeMismatch {
        let TypeMismatch { expected, found } = type_mismatch;
        let mut tm = ipc::TypeMismatch::new();
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
                let mut ipc_ee = effects.into();
                let mut deploy_result = ipc::DeployResult::new();
                let mut execution_result = ipc::DeployResult_ExecutionResult::new();
                execution_result.set_effects(ipc_ee);
                execution_result.set_cost(cost);
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
                    error @ EngineError::InvalidPublicKeyLength { .. } => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::WasmPreprocessingError(_) => {
                        precondition_failure(error.to_string())
                    }
                    error @ EngineError::WasmSerializationError(_) => {
                        precondition_failure(error.to_string())
                    }
                    EngineError::StorageError(storage_err) => {
                        execution_error(storage_err.to_string(), cost, effect)
                    }
                    error @ EngineError::AuthorizationFailure => {
                        precondition_failure(error.to_string())
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
                                tmp.set_cost(cost);
                                tmp.set_effects(effect.into());
                                tmp
                            };

                            deploy_result.set_execution_result(exec_result);
                            deploy_result
                        }
                        ExecutionError::KeyNotFound(key) => {
                            let msg = format!("Key {:?} not found.", key);
                            execution_error(msg, cost, effect)
                        }
                        ExecutionError::InvalidNonce {
                            deploy_nonce,
                            expected_nonce,
                        } if deploy_nonce <= expected_nonce => {
                            // Deploys with nonce lower than (or equal to) current account's nonce will always fail.
                            // They won't be repeated so we treat them as precondition failures.
                            let error_msg = format!("Deploy nonce: {:?} was lower (or equal to) than expected nonce {:?}", deploy_nonce, expected_nonce);
                            precondition_failure(error_msg)
                        }
                        ExecutionError::InvalidNonce {
                            deploy_nonce,
                            expected_nonce,
                        } => {
                            let mut deploy_result = ipc::DeployResult::new();
                            let mut invalid_nonce = ipc::DeployResult_InvalidNonce::new();
                            invalid_nonce.set_deploy_nonce(deploy_nonce);
                            invalid_nonce.set_expected_nonce(expected_nonce);
                            deploy_result.set_invalid_nonce(invalid_nonce);
                            deploy_result
                        }
                        ExecutionError::Revert(status) => {
                            let error_msg = format!("Exit code: {}", status);
                            execution_error(error_msg, cost, effect)
                        }
                        ExecutionError::Interpreter(error) => {
                            // If the error happens during contract execution it's mapped to HostError
                            // and wrapped in Interpreter error, so we may end up with InterpreterError(HostError(InterpreterError))).
                            // In order to provide clear error messages we have to downcast and match on the inner error,
                            // otherwise we end up with `Host(Trap(Trap(TrapKind:InterpreterError)))`.
                            // TODO: This really should be happening in the `Executor::exec`.
                            match error.as_host_error() {
                                Some(host_error) => {
                                    let downcasted_error =
                                        host_error.downcast_ref::<ExecutionError>().unwrap();
                                    match downcasted_error {
                                        ExecutionError::Revert(status) => {
                                            let errors_msg = format!("Exit code: {}", status);
                                            execution_error(errors_msg, cost, effect)
                                        }
                                        ExecutionError::KeyNotFound(key) => {
                                            let errors_msg = format!("Key {:?} not found.", key);
                                            execution_error(errors_msg, cost, effect)
                                        }
                                        other => {
                                            execution_error(format!("{:?}", other), cost, effect)
                                        }
                                    }
                                }

                                None => {
                                    let msg = format!("{:?}", error);
                                    execution_error(msg, cost, effect)
                                }
                            }
                        }
                        // TODO(mateusz.gorski): Be more specific about execution errors
                        other => {
                            let msg = format!("{:?}", other);
                            execution_error(msg, cost, effect)
                        }
                    },
                }
            }
        }
    }
}

pub fn grpc_response_from_commit_result<H>(
    prestate_hash: Blake2bHash,
    input: Result<CommitResult, H::Error>,
) -> ipc::CommitResponse
where
    H: History,
    H::Error: Into<EngineError> + std::fmt::Debug,
{
    match input {
        Ok(CommitResult::RootNotFound) => {
            logging::log_warning("RootNotFound");
            let mut root = ipc::RootNotFound::new();
            root.set_hash(prestate_hash.to_vec());
            let mut tmp_res = ipc::CommitResponse::new();
            tmp_res.set_missing_prestate(root);
            tmp_res
        }
        Ok(CommitResult::Success(post_state_hash)) => {
            let mut properties: BTreeMap<String, String> = BTreeMap::new();

            properties.insert(
                "post-state-hash".to_string(),
                format!("{:?}", post_state_hash),
            );

            properties.insert("success".to_string(), true.to_string());

            logging::log_details(
                log_level::LogLevel::Info,
                "effects applied; new state hash is: {post-state-hash}".to_owned(),
                properties,
            );

            let mut commit_result = ipc::CommitResult::new();
            let mut tmp_res = ipc::CommitResponse::new();
            commit_result.set_poststate_hash(post_state_hash.to_vec());
            tmp_res.set_success(commit_result);
            tmp_res
        }
        Ok(CommitResult::KeyNotFound(key)) => {
            logging::log_warning("KeyNotFound");
            let mut commit_response = ipc::CommitResponse::new();
            commit_response.set_key_not_found((&key).into());
            commit_response
        }
        Ok(CommitResult::TypeMismatch(type_mismatch)) => {
            logging::log_warning("TypeMismatch");
            let mut commit_response = ipc::CommitResponse::new();
            commit_response.set_type_mismatch(type_mismatch.into());
            commit_response
        }
        // TODO(mateusz.gorski): We should be more specific about errors here.
        Err(storage_error) => {
            let log_message = format!("storage error {:?} when applying effects", storage_error);
            logging::log_error(&log_message);
            let mut err = ipc::PostEffectsError::new();
            let mut tmp_res = ipc::CommitResponse::new();
            err.set_message(format!("{:?}", storage_error));
            tmp_res.set_failed_transform(err);
            tmp_res
        }
    }
}

/// Constructs an instance of [[ipc::DeployResult]] with an error set to [[ipc::DeployError_PreconditionFailure]].
fn precondition_failure(msg: String) -> ipc::DeployResult {
    let mut deploy_result = ipc::DeployResult::new();
    let mut precondition_failure = ipc::DeployResult_PreconditionFailure::new();
    precondition_failure.set_message(msg);
    deploy_result.set_precondition_failure(precondition_failure);
    deploy_result
}

/// Constructs an instance of [[ipc::DeployResult]] with error set to [[ipc::DeployError_ExecutionError]].
fn execution_error(msg: String, cost: u64, effect: ExecutionEffect) -> ipc::DeployResult {
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
        tmp.set_cost(cost);
        tmp.set_effects(effect.into());
        tmp
    };
    deploy_result.set_execution_result(execution_result);
    deploy_result
}

pub fn to_domain_validators(bond: &ipc::Bond) -> Result<(PublicKey, U512), String> {
    let pk = PublicKey::try_from(bond.get_validator_public_key())
        .map_err(|_| "Public key has to be exactly 32 bytes long.")?;
    match bond.get_stake().try_into() {
        Ok(bond) => Ok((pk, bond)),
        Err(err) => {
            let err_msg = format!("{:?}", err);
            Err(err_msg)
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::convert::TryInto;

    use proptest::prelude::*;

    use common::gens::{account_arb, contract_arb, key_arb, uref_map_arb, value_arb};
    use common::key::Key;
    use common::uref::{AccessRights, URef};
    use engine_server::mappings::CommitTransforms;
    use execution_engine::engine_state::error::Error::ExecError;
    use execution_engine::engine_state::error::{Error as EngineError, RootNotFound};
    use execution_engine::engine_state::execution_effect::ExecutionEffect;
    use execution_engine::engine_state::execution_result::ExecutionResult;
    use execution_engine::execution::Error;
    use shared::newtypes::Blake2bHash;
    use shared::transform::gens::transform_arb;
    use shared::transform::Transform;

    use super::execution_error;
    use super::ipc;
    use super::state;

    // Test that wasm_error function actually returns DeployResult with result set to WasmError
    #[test]
    fn wasm_error_result() {
        let error_msg = "ExecError";
        let mut result = execution_error(error_msg.to_owned(), 0, Default::default());
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
        let input_transforms: HashMap<Key, Transform> = {
            let mut tmp_map = HashMap::new();
            tmp_map.insert(
                Key::URef(URef::new([1u8; 32], AccessRights::ADD)),
                Transform::AddInt32(10),
            );
            tmp_map
        };
        let execution_effect: ExecutionEffect =
            ExecutionEffect::new(HashMap::new(), input_transforms.clone());
        let cost: u64 = 123;
        let execution_result: ExecutionResult = ExecutionResult::Success {
            effect: execution_effect,
            cost,
        };
        let mut ipc_deploy_result: ipc::DeployResult = execution_result.into();
        assert!(ipc_deploy_result.has_execution_result());
        let mut success = ipc_deploy_result.take_execution_result();
        assert_eq!(success.get_cost(), cost);

        // Extract transform map from the IPC message and parse it back to the domain
        let ipc_transforms: HashMap<Key, Transform> = {
            let mut ipc_effects = success.take_effects();
            let ipc_effects_tnfs = ipc_effects.take_transform_map().into_vec();
            ipc_effects_tnfs
                .iter()
                .map(|e| e.try_into())
                .collect::<Result<HashMap<Key, Transform>, _>>()
                .unwrap()
        };
        assert_eq!(&input_transforms, &ipc_transforms);
    }

    fn into_execution_failure<E: Into<EngineError>>(error: E, cost: u64) -> ExecutionResult {
        ExecutionResult::Failure {
            error: error.into(),
            effect: Default::default(),
            cost,
        }
    }

    fn test_cost<E: Into<EngineError>>(expected_cost: u64, err: E) -> u64 {
        let execution_failure = into_execution_failure(err, expected_cost);
        let ipc_deploy_result: ipc::DeployResult = execution_failure.into();
        assert!(ipc_deploy_result.has_execution_result());
        let success = ipc_deploy_result.get_execution_result();
        success.get_cost()
    }

    #[test]
    fn storage_error_has_cost() {
        use storage::error::Error::*;
        let cost: u64 = 100;
        // TODO: actually create an Rkv error
        // assert_eq!(test_cost(cost, RkvError("Error".to_owned())), cost);
        let bytesrepr_err = common::bytesrepr::Error::EarlyEndOfStream;
        assert_eq!(test_cost(cost, BytesRepr(bytesrepr_err)), cost);
    }

    #[test]
    fn exec_err_has_cost() {
        let cost: u64 = 100;
        // GasLimit error is treated differently at the moment so test separately
        assert_eq!(
            test_cost(cost, execution_engine::execution::Error::GasLimit),
            cost
        );
        // for the time being all other execution errors are treated in the same way
        let forged_ref_error = execution_engine::execution::Error::ForgedReference(URef::new(
            [1u8; 32],
            AccessRights::READ_ADD_WRITE,
        ));
        assert_eq!(test_cost(cost, forged_ref_error), cost);
    }

    #[test]
    fn commit_effects_merges_transforms() {
        // Tests that transforms made to the same key are merged instead of lost.
        let key = Key::Hash([1u8; 32]);
        let setup: Vec<ipc::TransformEntry> = {
            let transform_entry_first = {
                let mut tmp = ipc::TransformEntry::new();
                tmp.set_key((&key).into());
                tmp.set_transform(Transform::Write(common::value::Value::Int32(12)).into());
                tmp
            };
            let transform_entry_second = {
                let mut tmp = ipc::TransformEntry::new();
                tmp.set_key((&key).into());
                tmp.set_transform(Transform::AddInt32(10).into());
                tmp
            };
            vec![transform_entry_first, transform_entry_second]
        };
        let setup_slice: &[ipc::TransformEntry] = &setup;
        let commit: CommitTransforms = setup_slice
            .try_into()
            .expect("Transforming [state::TransformEntry] into CommitTransforms should work.");
        let expected_transform = Transform::Write(common::value::Value::Int32(22i32));
        let commit_transform = commit.get(&key);
        assert!(commit_transform.is_some());
        assert_eq!(expected_transform, *commit_transform.unwrap())
    }

    #[test]
    fn revert_error_maps_to_execution_error() {
        let revert_error = Error::Revert(10);
        let exec_result = ExecutionResult::Failure {
            error: ExecError(revert_error),
            effect: Default::default(),
            cost: 10,
        };
        let ipc_result: ipc::DeployResult = exec_result.into();
        assert!(ipc_result.has_execution_result());
        let ipc_execution_result = ipc_result.get_execution_result();
        assert_eq!(ipc_execution_result.cost, 10);
        assert_eq!(
            ipc_execution_result.get_error().get_exec_error().message,
            "Exit code: 10"
        );
    }

    proptest! {
        #[test]
        fn key_roundtrip(key in key_arb()) {
            let ipc_key: super::state::Key = (&key).into();
            let key_back: Key = (&ipc_key).try_into().expect("Transforming state::Key into domain Key should succeed.");
            assert_eq!(key_back, key)
        }

        #[test]
        fn uref_map_roundtrip(uref_map in uref_map_arb(10)) {
            let uref_map_newtype = super::URefMap(uref_map.clone());
            let ipc_uref_map: Vec<state::NamedKey> = uref_map_newtype.into();
            let ipc_urefs_slice: &[state::NamedKey] = &ipc_uref_map;
            let uref_map_back: super::URefMap = ipc_urefs_slice.try_into()
                .expect("Transforming Vec<state::NamedKey> to URefMap should succeed.");
            assert_eq!(uref_map, uref_map_back.0)
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
            let transform_entry: ipc::TransformEntry = (key, transform.clone()).into();
            let tuple: (Key, Transform) = (&transform_entry).try_into()
                .expect("Transforming TransformEntry into (Key, Transform) tuple should work.");
            assert_eq!(tuple, (key, transform))
        }

    }
}
