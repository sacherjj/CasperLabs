use std::collections::BTreeMap;

/// Helper method for turning instances of Value into Transform::Write.
fn transform_write(v: common::value::Value) -> storage::transform::Transform {
    storage::transform::Transform::Write(v)
}

/// Transforms ipc::Transform into domain transform::Transform.
fn ipc_transform_to_transform(tr: &super::ipc::Transform) -> storage::transform::Transform {
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

pub fn value_to_ipc(v: &common::value::Value) -> super::ipc::Value {
    let mut tv = super::ipc::Value::new();
    match v {
        common::value::Value::Int32(i) => {
            tv.set_integer(*i);
        }
        common::value::Value::ByteArray(arr) => {
            tv.set_byte_arr(arr.clone());
        }
        common::value::Value::ListInt32(list) => {
            let mut int_list = super::ipc::IntList::new();
            int_list.set_list(list.clone());
            tv.set_int_list(int_list);
        }
        common::value::Value::String(string) => {
            tv.set_string_val(string.clone());
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
                nk.set_key(key_to_ipc(key));
                nk
            };
            tv.set_named_key(named_key);
        }
        common::value::Value::Acct(account) => {
            let mut acc = super::ipc::Account::new();
            acc.set_pub_key(account.pub_key().to_vec());
            //TODO update proto; change nonce to u64
            acc.set_nonce(account.nonce() as i64);
            let urefs = urefs_map_to_ipc_vec(account.urefs_lookup());
            acc.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
            tv.set_account(acc);
        }
        common::value::Value::Contract { bytes, known_urefs } => {
            let mut contr = super::ipc::Contract::new();
            let urefs = urefs_map_to_ipc_vec(known_urefs);
            contr.set_body(bytes.clone());
            contr.set_known_urefs(protobuf::RepeatedField::from_vec(urefs));
            tv.set_contract(contr);
        }
    };
    tv
}

/// Transforms domain storage::transform::Transform into gRPC Transform.
fn transform_to_ipc(tr: &storage::transform::Transform) -> super::ipc::Transform {
    let mut t = super::ipc::Transform::new();
    match tr {
        storage::transform::Transform::Identity => {
            t.set_identity(super::ipc::TransformIdentity::new());
        }
        storage::transform::Transform::Write(v) => {
            let mut tw = super::ipc::TransformWrite::new();
            let tv = value_to_ipc(v);
            tw.set_value(tv);
            t.set_write(tw)
        }
        storage::transform::Transform::AddInt32(i) => {
            let mut add = super::ipc::TransformAddInt32::new();
            add.set_value(*i);
            t.set_add_i32(add);
        }
        storage::transform::Transform::AddKeys(keys) => {
            let mut add = super::ipc::TransformAddKeys::new();
            let keys = urefs_map_to_ipc_vec(keys);
            add.set_value(protobuf::RepeatedField::from_vec(keys));
            t.set_add_keys(add);
        }
        storage::transform::Transform::Failure(storage::transform::TypeMismatch {
            expected,
            found,
        }) => {
            let mut fail = super::ipc::TransformFailure::new();
            let mut typemismatch_err = super::ipc::StorageTypeMismatch::new();
            typemismatch_err.set_expected(expected.to_owned());
            typemismatch_err.set_found(found.to_owned());
            fail.set_error(typemismatch_err);
            t.set_failure(fail);
        }
    };
    t
}

// Helper method for turning gRPC Vec of NamedKey to domain BTreeMap.
fn ipc_vec_to_urefs_map(vec: &[super::ipc::NamedKey]) -> BTreeMap<String, common::key::Key> {
    let mut tree: BTreeMap<String, common::key::Key> = BTreeMap::new();
    for nk in vec {
        let _ = tree.insert(nk.get_name().to_string(), ipc_to_key(nk.get_key()));
    }
    tree
}

// Helper method for turning BTreeMap of Keys into Vec of gRPC NamedKey.
fn urefs_map_to_ipc_vec(urefs: &BTreeMap<String, common::key::Key>) -> Vec<super::ipc::NamedKey> {
    urefs
        .into_iter()
        .map(|(n, k)| {
            let mut nk = super::ipc::NamedKey::new();
            nk.set_name(n.to_string());
            nk.set_key(key_to_ipc(k));
            nk
        })
        .collect()
}

/// Transforms domain Key into gRPC Key.
fn key_to_ipc(key: &common::key::Key) -> super::ipc::Key {
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
        common::key::Key::URef(uref) => {
            let mut key_uref = super::ipc::KeyURef::new();
            key_uref.set_uref(uref.to_vec());
            k.set_uref(key_uref);
        }
    }
    k
}

/// Transforms gRPC Key into domain Key.
pub fn ipc_to_key(ipc_key: &super::ipc::Key) -> common::key::Key {
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
fn op_to_ipc(op: storage::op::Op) -> super::ipc::Op {
    let mut ipc_op = super::ipc::Op::new();
    match op {
        storage::op::Op::Read => ipc_op.set_read(super::ipc::ReadOp::new()),
        storage::op::Op::Write => ipc_op.set_write(super::ipc::WriteOp::new()),
        storage::op::Op::Add => ipc_op.set_add(super::ipc::AddOp::new()),
        storage::op::Op::NoOp => ipc_op.set_noop(super::ipc::NoOp::new()),
    };
    ipc_op
}

/// Transforms gRPC TransformEntry into domain tuple of (Key, Transform).
pub fn transform_entry_to_key_transform(
    te: &super::ipc::TransformEntry,
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
pub fn execution_effect_to_ipc(ee: storage::gs::ExecutionEffect) -> super::ipc::ExecutionEffect {
    let mut eff = super::ipc::ExecutionEffect::new();
    let ipc_ops: Vec<super::ipc::OpEntry> =
        ee.0.iter()
            .map(|(k, o)| {
                let mut op_entry = super::ipc::OpEntry::new();
                let ipc_key = key_to_ipc(k);
                let ipc_op = op_to_ipc(o.clone());
                op_entry.set_key(ipc_key);
                op_entry.set_operation(ipc_op);
                op_entry
            })
            .collect();
    let ipc_tran: Vec<super::ipc::TransformEntry> =
        ee.1.iter()
            .map(|(k, t)| {
                let mut tr_entry = super::ipc::TransformEntry::new();
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
