mod uint;

use protobuf::ProtobufEnum;
use std::collections::BTreeMap;
use std::convert::{TryFrom, TryInto};

use engine_server::ipc::{DeployResult_ExecutionResult, KeyURef_AccessRights};
use execution_engine::engine_state::error::{Error as EngineError, RootNotFound};
use execution_engine::engine_state::execution_effect::ExecutionEffect;
use execution_engine::engine_state::execution_result::ExecutionResult;
use execution_engine::engine_state::op::Op;
use execution_engine::execution::Error as ExecutionError;
use ipc;
use shared::logging;
use shared::logging::log_level;
use shared::newtypes::Blake2bHash;
use shared::transform::{self, TypeMismatch};
use storage::global_state::{CommitResult, History};

/// Helper method for turning instances of Value into Transform::Write.
fn transform_write(v: common::value::Value) -> Result<transform::Transform, ParsingError> {
    Ok(transform::Transform::Write(v))
}

#[derive(Debug)]
pub struct ParsingError(pub String);

/// Smart constructor for parse errors
fn parse_error<T>(message: String) -> Result<T, ParsingError> {
    Err(ParsingError(message))
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
        } else if tr.has_add_big_int() {
            let b = tr.get_add_big_int().get_value();
            let v = b.try_into()?;
            match v {
                common::value::Value::UInt128(u) => Ok(u.into()),
                common::value::Value::UInt256(u) => Ok(u.into()),
                common::value::Value::UInt512(u) => Ok(u.into()),
                other => parse_error(format!("Through some impossibility a RustBigInt was turned into a non-uint value type: ${:?}", other))
            }
        } else if tr.has_write() {
            let v = tr.get_write().get_value();
            if v.has_integer() {
                transform_write(common::value::Value::Int32(v.get_integer()))
            } else if v.has_big_int() {
                let b = v.get_big_int();
                let v = b.try_into()?;
                transform_write(v)
            } else if v.has_byte_arr() {
                let v: Vec<u8> = Vec::from(v.get_byte_arr());
                transform_write(common::value::Value::ByteArray(v))
            } else if v.has_int_list() {
                let list = v.get_int_list().list.clone();
                transform_write(common::value::Value::ListInt32(list))
            } else if v.has_string_val() {
                transform_write(common::value::Value::String(v.get_string_val().to_string()))
            } else if v.has_account() {
                let mut pub_key = [0u8; 32];
                let uref_map: URefMap = v.get_account().get_known_urefs().try_into()?;
                pub_key.clone_from_slice(&v.get_account().pub_key);
                let account =
                    common::value::Account::new(pub_key, v.get_account().nonce as u64, uref_map.0);
                transform_write(common::value::Value::Account(account))
            } else if v.has_contract() {
                let ipc_contr = v.get_contract();
                let contr_body = ipc_contr.get_body().to_vec();
                let known_urefs: URefMap = ipc_contr.get_known_urefs().try_into()?;
                let protocol_version = if ipc_contr.has_protocol_version() {
                    ipc_contr.get_protocol_version()
                } else {
                    return parse_error("Protocol version is not specified".to_string());
                };
                let contract = common::value::Contract::new(
                    contr_body,
                    known_urefs.0,
                    protocol_version.version,
                );
                transform_write(contract.into())
            } else if v.has_string_list() {
                let list = v.get_string_list().list.to_vec();
                transform_write(common::value::Value::ListString(list))
            } else if v.has_named_key() {
                let nk = v.get_named_key();
                let name = nk.get_name().to_string();
                let key = nk.get_key().try_into()?;
                transform_write(common::value::Value::NamedKey(name, key))
            } else {
                parse_error(format!(
                    "TransformEntry write contained unknown value: {:?}",
                    v
                ))
            }
        } else {
            parse_error("TransformEntry couldn't be parsed to known Transform.".to_owned())
        }
    }
}

impl From<common::value::Value> for super::ipc::Value {
    fn from(v: common::value::Value) -> Self {
        let mut tv = super::ipc::Value::new();
        match v {
            common::value::Value::Int32(i) => {
                tv.set_integer(i);
            }
            common::value::Value::UInt128(u) => tv.set_big_int(u.into()),
            common::value::Value::UInt256(u) => tv.set_big_int(u.into()),
            common::value::Value::UInt512(u) => tv.set_big_int(u.into()),
            common::value::Value::ByteArray(arr) => {
                tv.set_byte_arr(arr);
            }
            common::value::Value::ListInt32(list) => {
                let mut int_list = super::ipc::IntList::new();
                int_list.set_list(list);
                tv.set_int_list(int_list);
            }
            common::value::Value::String(string) => {
                tv.set_string_val(string);
            }
            common::value::Value::ListString(list_string) => {
                let mut string_list = super::ipc::StringList::new();
                string_list.set_list(protobuf::RepeatedField::from_ref(list_string));
                tv.set_string_list(string_list);
            }
            common::value::Value::NamedKey(name, key) => {
                let named_key = {
                    let mut nk = super::ipc::NamedKey::new();
                    nk.set_name(name.to_string());
                    nk.set_key((&key).into());
                    nk
                };
                tv.set_named_key(named_key);
            }
            common::value::Value::Account(account) => {
                let mut acc = super::ipc::Account::new();
                acc.set_pub_key(account.pub_key().to_vec());
                acc.set_nonce(account.nonce());
                let urefs = URefMap(account.get_urefs_lookup()).into();
                acc.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
                tv.set_account(acc);
            }
            common::value::Value::Contract(contract) => {
                let (bytes, known_urefs, protocol_version) = contract.destructure();
                let mut contr = super::ipc::Contract::new();
                let urefs = URefMap(known_urefs).into();
                contr.set_body(bytes);
                contr.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
                let mut protocol = super::ipc::ProtocolVersion::new();
                protocol.set_version(protocol_version);
                contr.set_protocol_version(protocol);
                tv.set_contract(contr);
            }
        };
        tv
    }
}

fn add_big_int_transform<U: Into<super::ipc::RustBigInt>>(t: &mut super::ipc::Transform, u: U) {
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
            transform::Transform::Failure(transform::Error::Overflow) => {
                let mut fail = super::ipc::TransformFailure::new();
                let mut overflow_err = super::ipc::AdditionOverflow::new();
                fail.set_overflow(overflow_err);
                t.set_failure(fail);
            }
        };
        t
    }
}

// newtype because trait impl have to be defined in the crate of the type.
pub struct URefMap(BTreeMap<String, common::key::Key>);

// Helper method for turning gRPC Vec of NamedKey to domain BTreeMap.
impl TryFrom<&[super::ipc::NamedKey]> for URefMap {
    type Error = ParsingError;
    fn try_from(from: &[super::ipc::NamedKey]) -> Result<Self, ParsingError> {
        let mut tree: BTreeMap<String, common::key::Key> = BTreeMap::new();
        for nk in from {
            let name = nk.get_name().to_string();
            let key = nk.get_key().try_into()?;
            let _ = tree.insert(name, key);
        }
        Ok(URefMap(tree))
    }
}

impl From<URefMap> for Vec<super::ipc::NamedKey> {
    fn from(uref_map: URefMap) -> Vec<super::ipc::NamedKey> {
        uref_map
            .0
            .into_iter()
            .map(|(n, k)| {
                let mut nk = super::ipc::NamedKey::new();
                nk.set_name(n);
                nk.set_key((&k).into());
                nk
            })
            .collect()
    }
}

impl From<&common::key::Key> for super::ipc::Key {
    fn from(key: &common::key::Key) -> super::ipc::Key {
        let mut k = super::ipc::Key::new();
        match key {
            common::key::Key::Account(acc) => {
                let mut key_addr = super::ipc::KeyAddress::new();
                key_addr.set_account(acc.to_vec());
                k.set_account(key_addr);
            }
            common::key::Key::Hash(hash) => {
                let mut key_hash = super::ipc::KeyHash::new();
                key_hash.set_key(hash.to_vec());
                k.set_hash(key_hash);
            }
            common::key::Key::URef(uref, access_rights) => {
                let mut key_uref = super::ipc::KeyURef::new();
                key_uref.set_uref(uref.to_vec());
                key_uref.set_access_rights(
                    KeyURef_AccessRights::from_i32(access_rights.bits().into()).unwrap(),
                );
                k.set_uref(key_uref);
            }
            common::key::Key::Local { seed, key_hash } => {
                let mut key_local = super::ipc::KeyLocal::new();
                key_local.set_seed(seed.to_vec());
                key_local.set_key_hash(key_hash.to_vec());
                k.set_local(key_local);
            }
        }
        k
    }
}

impl TryFrom<&super::ipc::Key> for common::key::Key {
    type Error = ParsingError;

    fn try_from(ipc_key: &super::ipc::Key) -> Result<Self, ParsingError> {
        if ipc_key.has_account() {
            let mut arr = [0u8; 32];
            arr.clone_from_slice(&ipc_key.get_account().account);
            Ok(common::key::Key::Account(arr))
        } else if ipc_key.has_hash() {
            let mut arr = [0u8; 32];
            arr.clone_from_slice(&ipc_key.get_hash().key);
            Ok(common::key::Key::Hash(arr))
        } else if ipc_key.has_uref() {
            let mut arr = [0u8; 32];
            arr.clone_from_slice(&ipc_key.get_uref().uref);
            let access_rights = common::key::AccessRights::from_bits(
                ipc_key.get_uref().access_rights.value().try_into().unwrap(),
            )
            .unwrap();
            Ok(common::key::Key::URef(arr, access_rights))
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

impl From<ExecutionEffect> for super::ipc::ExecutionEffect {
    fn from(ee: ExecutionEffect) -> super::ipc::ExecutionEffect {
        let mut eff = super::ipc::ExecutionEffect::new();
        let ipc_ops: Vec<super::ipc::OpEntry> =
            ee.0.iter()
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
            ee.1.into_iter()
                .map(|(k, t)| {
                    let mut tr_entry = super::ipc::TransformEntry::new();

                    let ipc_tr = t.into();
                    tr_entry.set_key((&k).into());
                    tr_entry.set_transform(ipc_tr);
                    tr_entry
                })
                .collect();
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
                deploy_result.set_executionResult(execution_result);
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
                    EngineError::StorageError(storage_err) => {
                        wasm_error(storage_err.to_string(), cost, effect)
                    }
                    EngineError::PreprocessingError(err_msg) => wasm_error(err_msg, cost, effect),
                    EngineError::ExecError(exec_error) => match exec_error {
                        ExecutionError::GasLimit => {
                            let mut deploy_result = ipc::DeployResult::new();
                            let deploy_error = {
                                let mut tmp = ipc::DeployError::new();
                                tmp.set_gasErr(ipc::DeployError_OutOfGasError::new());
                                tmp
                            };
                            let exec_result = {
                                let mut tmp = DeployResult_ExecutionResult::new();
                                tmp.set_error(deploy_error);
                                tmp.set_cost(cost);
                                tmp.set_effects(effect.into());
                                tmp
                            };

                            deploy_result.set_executionResult(exec_result);
                            deploy_result
                        }
                        ExecutionError::KeyNotFound(key) => {
                            let msg = format!("Key {:?} not found.", key);
                            wasm_error(msg, cost, effect)
                        }
                        ExecutionError::InvalidNonce(nonce) => {
                            let mut deploy_result = ipc::DeployResult::new();
                            let mut invalid_nonce = ipc::DeployResult_InvalidNonce::new();
                            invalid_nonce.set_nonce(nonce);
                            deploy_result.set_invalidNonce(invalid_nonce);
                            deploy_result
                        }
                        // TODO(mateusz.gorski): Be more specific about execution errors
                        other => {
                            let msg = format!("{:?}", other);
                            wasm_error(msg, cost, effect)
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
        Ok(CommitResult::Overflow) => {
            logging::log_warning("Overflow");
            let overflow = ipc::AdditionOverflow::new();
            let mut tmp_res = ipc::CommitResponse::new();
            tmp_res.set_overflow(overflow);
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

fn wasm_error(msg: String, cost: u64, effect: ExecutionEffect) -> ipc::DeployResult {
    let mut deploy_result = ipc::DeployResult::new();
    let deploy_error = {
        let mut tmp = ipc::DeployError::new();
        let mut err = ipc::DeployError_WasmError::new();
        err.set_message(msg.to_owned());
        tmp.set_wasmErr(err);
        tmp
    };
    let execution_result = {
        let mut tmp = DeployResult_ExecutionResult::new();
        tmp.set_error(deploy_error);
        tmp.set_cost(cost);
        tmp.set_effects(effect.into());
        tmp
    };
    deploy_result.set_executionResult(execution_result);
    deploy_result
}

#[cfg(test)]
mod tests {
    use super::wasm_error;
    use common::key::AccessRights;
    use common::key::Key;
    use execution_engine::engine_state::error::{Error as EngineError, RootNotFound};
    use execution_engine::engine_state::execution_effect::ExecutionEffect;
    use execution_engine::engine_state::execution_result::ExecutionResult;
    use shared::newtypes::Blake2bHash;
    use shared::transform::Transform;
    use std::collections::HashMap;
    use std::convert::TryInto;

    // Test that wasm_error function actually returns DeployResult with result set to WasmError
    #[test]
    fn wasm_error_result() {
        let error_msg = "WasmError";
        let mut result = wasm_error(error_msg.to_owned(), 0, Default::default());
        assert!(result.has_executionResult());
        let mut exec_result = result.take_executionResult();
        assert!(exec_result.has_error());
        let mut ipc_error = exec_result.take_error();
        assert!(ipc_error.has_wasmErr());
        let ipc_wasm_error = ipc_error.take_wasmErr();
        let ipc_error_msg = ipc_wasm_error.get_message();
        assert_eq!(ipc_error_msg, error_msg);
    }

    #[test]
    fn deploy_result_to_ipc_missing_root() {
        let root_hash: Blake2bHash = [1u8; 32].into();
        let mut result: super::ipc::RootNotFound = RootNotFound(root_hash).into();
        let ipc_missing_hash = result.take_hash();
        assert_eq!(root_hash.to_vec(), ipc_missing_hash);
    }

    #[test]
    fn deploy_result_to_ipc_success() {
        let input_transforms: HashMap<Key, Transform> = {
            let mut tmp_map = HashMap::new();
            tmp_map.insert(
                Key::URef([1u8; 32], AccessRights::ADD),
                Transform::AddInt32(10),
            );
            tmp_map
        };
        let execution_effect: ExecutionEffect =
            ExecutionEffect(HashMap::new(), input_transforms.clone());
        let cost: u64 = 123;
        let execution_result: ExecutionResult = ExecutionResult::Success {
            effect: execution_effect,
            cost,
        };
        let mut ipc_deploy_result: super::ipc::DeployResult = execution_result.into();
        assert!(ipc_deploy_result.has_executionResult());
        let mut success = ipc_deploy_result.take_executionResult();
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
        let ipc_deploy_result: super::ipc::DeployResult = execution_failure.into();
        assert!(ipc_deploy_result.has_executionResult());
        let success = ipc_deploy_result.get_executionResult();
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
    fn preprocessing_err_has_cost() {
        let cost: u64 = 100;
        // it doesn't matter what error type it is
        let preprocessing_error = wasm_prep::PreprocessingError::NoExportSection;
        assert_eq!(test_cost(cost, preprocessing_error), cost);
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
        let forged_ref_error =
            execution_engine::execution::Error::ForgedReference(Key::Account([1u8; 32]));
        assert_eq!(test_cost(cost, forged_ref_error), cost);
    }
}
