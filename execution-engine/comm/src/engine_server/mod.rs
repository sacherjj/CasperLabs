pub mod ipc;
pub mod ipc_grpc;

use execution_engine::engine::EngineState;
use ipc::DeployResult;
use ipc_grpc::ExecutionEngineService;
use std::collections::BTreeMap;
use std::marker::{Send, Sync};
use storage::{GlobalState, TrackingCopy};

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<T: TrackingCopy, G: GlobalState<T>> ipc_grpc::ExecutionEngineService for EngineState<T, G> {
    fn send_deploy(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::Deploy,
    ) -> grpc::SingleResponse<ipc::DeployResult> {
        let mut addr = [0u8; 20];
        addr.copy_from_slice(&p.address);
        match self.run_deploy(&p.session_code, addr, &(p.gas_limit as u64)) {
            Ok(ee) => {
                let mut res = DeployResult::new();
                res.set_effects(execution_effect_to_ipc(ee));
                grpc::SingleResponse::completed(res)
            }
            //TODO better error handling
            Err(ee_error) => {
                let mut res = DeployResult::new();
                let mut err = ipc::DeployError::new();
                let mut wasm_err = ipc::WasmError::new();
                wasm_err.set_message(format!("{:?}", ee_error));
                err.set_wasmErr(wasm_err);
                res.set_error(err);
                grpc::SingleResponse::completed(res)
            }
        }
    }

    fn execute_effects(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::CommutativeEffects,
    ) -> grpc::SingleResponse<ipc::PostEffectsResult> {
        let r: Result<(), execution_engine::engine::Error> = p
            .get_effects()
            .iter()
            .map(|e| transform_entry_to_key_transform(e))
            .try_fold((), |_, (k, t)| {
                let res = self.apply_effect(k, t);
                match &res {
                    //TODO: instead of println! this should be logged
                    Ok(_) => println!("Applied effects for {:?}", k),
                    Err(e) => println!("Error {:?} when applying effects for {:?}", e, k),
                };
                res
            });
        let res = {
            let mut tmp_res = ipc::PostEffectsResult::new();
            match r {
                Ok(_) => {
                    tmp_res.set_success(ipc::Done::new());
                    tmp_res
                }
                Err(e) => {
                    let mut err = ipc::PostEffectsError::new();
                    err.set_message(format!("{:?}", e));
                    tmp_res.set_error(err);
                    tmp_res
                }
            }
        };
        grpc::SingleResponse::completed(res)
    }
}

/// Helper method for turning instances of Value into Transform::Write.
fn transform_write(v: common::value::Value) -> storage::transform::Transform {
    storage::transform::Transform::Write(v)
}

/// Transforms ipc::Transform into domain transform::Transform.
fn ipc_transform_to_transform(tr: &ipc::Transform) -> storage::transform::Transform {
    if tr.has_identity() {
        storage::transform::Transform::Identity
    } else if tr.has_add_keys() {
        let add_keys = tr
            .get_add_keys()
            .get_value()
            .iter()
            .map(|nk| (nk.name.to_string(), ipc_to_key(nk.get_key())));
        let keys_map = {
            let mut map = BTreeMap::new();
            for (n, k) in add_keys {
                map.insert(n, k);
            }
            map
        };
        storage::transform::Transform::AddKeys(keys_map)
    } else if tr.has_add_i32() {
        storage::transform::Transform::AddInt32(tr.get_add_i32().value)
    } else if tr.has_write() {
        let v = tr.get_write().get_value();
        if v.has_integer() {
            transform_write(common::value::Value::Int32(v.get_integer()))
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
            pub_key.clone_from_slice(&v.get_account().pub_key);
            let account = common::value::Account::new(
                pub_key,
                v.get_account().nonce as u64,
                ipc_vec_to_urefs_map(&v.get_account().known_urefs.to_vec()),
            );
            transform_write(common::value::Value::Acct(account))
        } else if v.has_contract() {
            let ipc_contr = v.get_contract();
            let contr_body = ipc_contr.get_body().to_vec();
            let known_urefs = ipc_vec_to_urefs_map(ipc_contr.get_known_urefs());
            transform_write(common::value::Value::Contract {
                bytes: contr_body,
                known_urefs: known_urefs,
            })
        } else if v.has_string_list() {
            let list = v.get_string_list().list.to_vec();
            transform_write(common::value::Value::ListString(list))
        } else if v.has_named_key() {
            let nk = v.get_named_key();
            let name = nk.get_name().to_string();
            let key = ipc_to_key(nk.get_key());
            transform_write(common::value::Value::NamedKey(name, key))
        } else {
            panic!("TransformEntry write contained unknown value: {:?}", v)
        }
    } else {
        panic!("TransformEntry couldn't be parsed to known Transform.")
    }
}

/// Transforms domain storage::transform::Transform into gRPC Transform.
fn transform_to_ipc(tr: &storage::transform::Transform) -> ipc::Transform {
    let mut t = ipc::Transform::new();
    match tr {
        storage::transform::Transform::Identity => {
            t.set_identity(ipc::TransformIdentity::new());
        }
        storage::transform::Transform::Write(v) => {
            let mut tw = ipc::TransformWrite::new();
            let mut tv = ipc::Value::new();
            match v {
                common::value::Value::Int32(i) => {
                    tv.set_integer(*i);
                }
                common::value::Value::ByteArray(arr) => {
                    tv.set_byte_arr(arr.clone());
                }
                common::value::Value::ListInt32(list) => {
                    let mut int_list = ipc::IntList::new();
                    int_list.set_list(list.clone());
                    tv.set_int_list(int_list);
                }
                common::value::Value::String(string) => {
                    tv.set_string_val(string.clone());
                }
                common::value::Value::ListString(list_string) => {
                    let mut string_list = ipc::StringList::new();
                    string_list.set_list(protobuf::RepeatedField::from_ref(list_string));
                    tv.set_string_list(string_list);
                }
                common::value::Value::NamedKey(name, key) => {
                    let named_key = {
                        let mut nk = ipc::NamedKey::new();
                        nk.set_name(name.to_string());
                        nk.set_key(key_to_ipc(key));
                        nk
                    };
                    tv.set_named_key(named_key);
                }
                common::value::Value::Acct(account) => {
                    let mut acc = ipc::Account::new();
                    acc.set_pub_key(account.pub_key().to_vec());
                    //TODO update proto; change nonce to u64
                    acc.set_nonce(account.nonce() as i64);
                    let urefs = urefs_map_to_ipc_vec(account.urefs_lookup());
                    acc.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
                    tv.set_account(acc);
                }
                common::value::Value::Contract { bytes, known_urefs } => {
                    let mut contr = ipc::Contract::new();
                    let urefs = urefs_map_to_ipc_vec(known_urefs);
                    contr.set_body(bytes.clone());
                    contr.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
                    tv.set_contract(contr);
                }
            };
            tw.set_value(tv);
            t.set_write(tw)
        }
        storage::transform::Transform::AddInt32(i) => {
            let mut add = ipc::TransformAddInt32::new();
            add.set_value(*i);
            t.set_add_i32(add);
        }
        storage::transform::Transform::AddKeys(keys) => {
            let mut add = ipc::TransformAddKeys::new();
            let keys = urefs_map_to_ipc_vec(keys);
            add.set_value(protobuf::RepeatedField::from_vec(keys));
            t.set_add_keys(add);
        }
        storage::transform::Transform::Failure(f) => {
            let mut fail = ipc::TransformFailure::new();
            let mut stor_err = ipc::StorageError::new();
            match f {
                storage::Error::KeyNotFound { key } => {
                    stor_err.set_key_not_found(key_to_ipc(key));
                }
                storage::Error::TypeMismatch { expected, found } => {
                    let mut type_miss = ipc::StorageTypeMismatch::new();
                    type_miss.set_expected(expected.to_string());
                    type_miss.set_found(found.to_string());
                    stor_err.set_type_mismatch(type_miss);
                }
            };
            fail.set_error(stor_err);
            t.set_failure(fail);
        }
    };
    t
}

// Helper method for turning gRPC Vec of NamedKey to domain BTreeMap.
fn ipc_vec_to_urefs_map(vec: &[ipc::NamedKey]) -> BTreeMap<String, common::key::Key> {
    let mut tree: BTreeMap<String, common::key::Key> = BTreeMap::new();
    for nk in vec {
        let _ = tree.insert(nk.get_name().to_string(), ipc_to_key(nk.get_key()));
    }
    tree
}

// Helper method for turning BTreeMap of Keys into Vec of gRPC NamedKey.
fn urefs_map_to_ipc_vec(urefs: &BTreeMap<String, common::key::Key>) -> Vec<ipc::NamedKey> {
    urefs
        .into_iter()
        .map(|(n, k)| {
            let mut nk = ipc::NamedKey::new();
            nk.set_name(n.to_string());
            nk.set_key(key_to_ipc(k));
            nk
        })
        .collect()
}

/// Transforms domain Key into gRPC Key.
fn key_to_ipc(key: &common::key::Key) -> ipc::Key {
    let mut k = ipc::Key::new();
    match key {
        common::key::Key::Account(acc) => {
            let mut key_addr = ipc::KeyAddress::new();
            key_addr.set_account(acc.to_vec());
            k.set_account(key_addr);
        }
        common::key::Key::Hash(hash) => {
            let mut key_hash = ipc::KeyHash::new();
            key_hash.set_key(hash.to_vec());
            k.set_hash(key_hash);
        }
        common::key::Key::URef(uref) => {
            let mut key_uref = ipc::KeyURef::new();
            key_uref.set_uref(uref.to_vec());
            k.set_uref(key_uref);
        }
    }
    k
}

/// Transforms gRPC Key into domain Key.
fn ipc_to_key(ipc_key: &ipc::Key) -> common::key::Key {
    if ipc_key.has_account() {
        let mut arr = [0u8; 20];
        arr.clone_from_slice(&ipc_key.get_account().account);
        common::key::Key::Account(arr)
    } else if ipc_key.has_hash() {
        let mut arr = [0u8; 32];
        arr.clone_from_slice(&ipc_key.get_hash().key);
        common::key::Key::Hash(arr)
    } else if ipc_key.has_uref() {
        let mut arr = [0u8; 32];
        arr.clone_from_slice(&ipc_key.get_uref().uref);
        common::key::Key::URef(arr)
    } else {
        //TODO make this Result::Err instead of panic
        panic!(format!(
            "ipc Key couldn't be parsed to any Key: {:?}",
            ipc_key
        ))
    }
}

/// Transform domain Op into gRPC Op.
fn op_to_ipc(op: storage::op::Op) -> ipc::Op {
    let mut ipc_op = ipc::Op::new();
    match op {
        storage::op::Op::Read => ipc_op.set_read(ipc::ReadOp::new()),
        storage::op::Op::Write => ipc_op.set_write(ipc::WriteOp::new()),
        storage::op::Op::Add => ipc_op.set_add(ipc::AddOp::new()),
        storage::op::Op::NoOp => ipc_op.set_noop(ipc::NoOp::new()),
    };
    ipc_op
}

/// Transforms gRPC TransformEntry into domain tuple of (Key, Transform).
fn transform_entry_to_key_transform(
    te: &ipc::TransformEntry,
) -> (common::key::Key, storage::transform::Transform) {
    if te.has_key() {
        let key = ipc_to_key(te.get_key());
        if te.has_transform() {
            let t: storage::transform::Transform = ipc_transform_to_transform(te.get_transform());
            (key, t)
        } else {
            panic!("No transform field in TransformEntry")
        }
    } else {
        panic!("No key field in TransformEntry")
    }
}

/// Transforms domain ExecutionEffect into gRPC ExecutionEffect.
fn execution_effect_to_ipc(ee: storage::ExecutionEffect) -> ipc::ExecutionEffect {
    let mut eff = ipc::ExecutionEffect::new();
    let ipc_ops: Vec<ipc::OpEntry> =
        ee.0.iter()
            .map(|(k, o)| {
                let mut op_entry = ipc::OpEntry::new();
                let ipc_key = key_to_ipc(k);
                let ipc_op = op_to_ipc(o.clone());
                op_entry.set_key(ipc_key);
                op_entry.set_operation(ipc_op);
                op_entry
            })
            .collect();
    let ipc_tran: Vec<ipc::TransformEntry> =
        ee.1.iter()
            .map(|(k, t)| {
                let mut tr_entry = ipc::TransformEntry::new();
                let ipc_key = key_to_ipc(k);
                let ipc_tr = transform_to_ipc(t);
                tr_entry.set_key(ipc_key);
                tr_entry.set_transform(ipc_tr);
                tr_entry
            })
            .collect();
    eff.set_op_map(protobuf::RepeatedField::from_vec(ipc_ops));
    eff.set_transform_map(protobuf::RepeatedField::from_vec(ipc_tran));
    eff
}

pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    e: E,
) -> grpc::ServerBuilder {
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let mut server = grpc::ServerBuilder::new_plain();
    server.http.set_unix_addr(socket.to_owned()).unwrap();
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
