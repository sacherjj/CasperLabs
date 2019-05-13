#![feature(test)]

extern crate casperlabs_contract_ffi;

use std::collections::BTreeMap;
use std::iter;

extern crate test;
use test::black_box;
use test::Bencher;

use casperlabs_contract_ffi::bytesrepr::{FromBytes, ToBytes};
use casperlabs_contract_ffi::key::{AccessRights, Key};
use casperlabs_contract_ffi::value::{
    account::Account,
    contract::Contract,
    uint::{U128, U256, U512},
    Value,
};

static KB: usize = 1024;
static BATCH: usize = 4 * KB;

fn prepare_vector(size: usize) -> Vec<i32> {
    (0..size as i32).collect()
}

#[bench]
fn serialize_vector_of_i32s(b: &mut Bencher) {
    let data = prepare_vector(black_box(BATCH));
    b.iter(|| data.to_bytes());
}

#[bench]
fn deserialize_vector_of_i32s(b: &mut Bencher) {
    let data = prepare_vector(black_box(BATCH)).to_bytes().unwrap();
    b.iter(|| {
        let (res, _rem): (Vec<i32>, _) = FromBytes::from_bytes(&data).unwrap();
        res
    });
}

#[bench]
fn serialize_vector_of_u8(b: &mut Bencher) {
    // 0, 1, ... 254, 255, 0, 1, ...
    let data: Vec<u8> = prepare_vector(BATCH)
        .into_iter()
        .map(|value| value as u8)
        .collect::<Vec<_>>();
    b.iter(|| data.to_bytes());
}

#[bench]
fn deserialize_vector_of_u8(b: &mut Bencher) {
    // 0, 1, ... 254, 255, 0, 1, ...
    let data: Vec<u8> = prepare_vector(BATCH)
        .into_iter()
        .map(|value| value as u8)
        .collect::<Vec<_>>()
        .to_bytes()
        .unwrap();
    b.iter(|| Vec::<i32>::from_bytes(&data))
}

#[bench]
fn serialize_u8(b: &mut Bencher) {
    b.iter(|| ToBytes::to_bytes(black_box(&129u8)));
}
#[bench]
fn deserialize_u8(b: &mut Bencher) {
    b.iter(|| u8::from_bytes(black_box(&[129u8])));
}

#[bench]
fn serialize_i32(b: &mut Bencher) {
    b.iter(|| ToBytes::to_bytes(black_box(&1_816_142_132i32)));
}
#[bench]
fn deserialize_i32(b: &mut Bencher) {
    b.iter(|| i32::from_bytes(black_box(&[0x34, 0x21, 0x40, 0x6c])));
}

#[bench]
fn serialize_u64(b: &mut Bencher) {
    b.iter(|| ToBytes::to_bytes(black_box(&14_157_907_845_468_752_670u64)));
}
#[bench]
fn deserialize_u64(b: &mut Bencher) {
    b.iter(|| u64::from_bytes(black_box(&[0x1e, 0x8b, 0xe1, 0x73, 0x2c, 0xfe, 0x7a, 0xc4])));
}

#[bench]
fn serialize_some_u64(b: &mut Bencher) {
    let data = Some(14_157_907_845_468_752_670u64);

    b.iter(|| ToBytes::to_bytes(black_box(&data)));
}
#[bench]
fn deserialize_some_u64(b: &mut Bencher) {
    let data = Some(14_157_907_845_468_752_670u64);
    let data = data.to_bytes().unwrap();

    b.iter(|| Option::<u64>::from_bytes(&data));
}

#[bench]
fn serialize_none_u64(b: &mut Bencher) {
    let data: Option<u64> = None;

    b.iter(|| ToBytes::to_bytes(black_box(&data)));
}

#[bench]
fn deserialize_ok_u64(b: &mut Bencher) {
    let data: Option<u64> = None;
    let data = data.to_bytes().unwrap();
    b.iter(|| Option::<u64>::from_bytes(&data));
}

#[bench]
fn serialize_vector_of_vector_of_u8(b: &mut Bencher) {
    let data: Vec<Vec<u8>> = (0..4)
        .map(|_v| {
            // 0, 1, 2, ..., 254, 255
            iter::repeat_with(|| 0..255u8)
                .flatten()
                // 4 times to create 4x 1024 bytes
                .take(4)
                .collect::<Vec<_>>()
        })
        .collect::<Vec<Vec<_>>>();

    b.iter(|| data.to_bytes());
}

#[bench]
fn deserialize_vector_of_vector_of_u8(b: &mut Bencher) {
    let data: Vec<u8> = (0..4)
        .map(|_v| {
            // 0, 1, 2, ..., 254, 255
            iter::repeat_with(|| 0..255u8)
                .flatten()
                // 4 times to create 4x 1024 bytes
                .take(4)
                .collect::<Vec<_>>()
        })
        .collect::<Vec<Vec<_>>>()
        .to_bytes()
        .unwrap();
    b.iter(|| Vec::<Vec<u8>>::from_bytes(&data));
}

#[bench]
fn serialize_tree_map(b: &mut Bencher) {
    let data = {
        let mut res = BTreeMap::new();
        res.insert("asdf".to_string(), "zxcv".to_string());
        res.insert("qwer".to_string(), "rewq".to_string());
        res.insert("1234".to_string(), "5678".to_string());
        res
    };

    b.iter(|| ToBytes::to_bytes(black_box(&data)));
}

#[bench]
fn deserialize_treemap(b: &mut Bencher) {
    let data = {
        let mut res = BTreeMap::new();
        res.insert("asdf".to_string(), "zxcv".to_string());
        res.insert("qwer".to_string(), "rewq".to_string());
        res.insert("1234".to_string(), "5678".to_string());
        res
    };
    let data = data.clone().to_bytes().unwrap();
    b.iter(|| BTreeMap::<String, String>::from_bytes(black_box(&data)));
}

#[bench]
fn serialize_string(b: &mut Bencher) {
    let lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";
    let data = lorem.to_string();
    b.iter(|| ToBytes::to_bytes(black_box(&data)));
}

#[bench]
fn deserialize_string(b: &mut Bencher) {
    let lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";
    let data = lorem.to_bytes().unwrap();
    b.iter(|| String::from_bytes(&data));
}

#[bench]
fn serialize_vec_of_string(b: &mut Bencher) {
    let lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.".to_string();
    let array_of_lorem: Vec<String> = lorem.split(' ').map(Into::into).collect();
    let data = array_of_lorem.clone();
    b.iter(|| ToBytes::to_bytes(black_box(&data)));
}

#[bench]
fn deserialize_vec_of_string(b: &mut Bencher) {
    let lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.".to_string();
    let array_of_lorem: Vec<String> = lorem.split(' ').map(Into::into).collect();
    let data = array_of_lorem.to_bytes().unwrap();

    b.iter(|| Vec::<String>::from_bytes(&data));
}

#[bench]
fn serialize_unit(b: &mut Bencher) {
    b.iter(|| ToBytes::to_bytes(black_box(&())))
}

#[bench]
fn deserialize_unit(b: &mut Bencher) {
    let data = ().to_bytes().unwrap();

    b.iter(|| <()>::from_bytes(&data))
}

#[bench]
fn serialize_key_account(b: &mut Bencher) {
    let account = Key::Account([0u8; 20]);

    b.iter(|| ToBytes::to_bytes(black_box(&account)))
}

#[bench]
fn deserialize_key_account(b: &mut Bencher) {
    let account = Key::Account([0u8; 20]);
    let account_bytes = account.to_bytes().unwrap();

    b.iter(|| Key::from_bytes(black_box(&account_bytes)))
}

#[bench]
fn serialize_key_hash(b: &mut Bencher) {
    let hash = Key::Hash([0u8; 32]);
    b.iter(|| ToBytes::to_bytes(black_box(&hash)))
}
#[bench]
fn deserialize_key_hash(b: &mut Bencher) {
    let hash = Key::Hash([0u8; 32]);
    let hash_bytes = hash.to_bytes().unwrap();

    b.iter(|| Key::from_bytes(black_box(&hash_bytes)))
}

#[bench]
fn serialize_key_uref(b: &mut Bencher) {
    let uref = Key::URef([0u8; 32], AccessRights::ADD_WRITE);
    b.iter(|| ToBytes::to_bytes(black_box(&uref)))
}
#[bench]
fn deserialize_key_uref(b: &mut Bencher) {
    let uref = Key::URef([0u8; 32], AccessRights::ADD_WRITE);
    let uref_bytes = uref.to_bytes().unwrap();

    b.iter(|| Key::from_bytes(black_box(&uref_bytes)))
}

#[bench]
fn serialize_vec_of_keys(b: &mut Bencher) {
    let keys: Vec<Key> = (0..32)
        .map(|i| Key::URef([i; 32], AccessRights::ADD_WRITE))
        .collect();
    b.iter(|| ToBytes::to_bytes(black_box(&keys)))
}

#[bench]
fn deserialize_vec_of_keys(b: &mut Bencher) {
    let keys: Vec<Key> = (0..32)
        .map(|i| Key::URef([i; 32], AccessRights::ADD_WRITE))
        .collect();
    let keys_bytes = keys.clone().to_bytes().unwrap();
    b.iter(|| Vec::<Key>::from_bytes(black_box(&keys_bytes)));
}

#[bench]
fn serialize_accessrights_read(b: &mut Bencher) {
    b.iter(|| AccessRights::READ.to_bytes());
}
#[bench]
fn deserialize_accessrights_read(b: &mut Bencher) {
    let data = AccessRights::READ.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}
#[bench]
fn serialize_accessrights_write(b: &mut Bencher) {
    b.iter(|| AccessRights::WRITE.to_bytes());
}
#[bench]
fn deserialize_accessrights_write(b: &mut Bencher) {
    let data = AccessRights::WRITE.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}
#[bench]
fn serialize_accessrights_add(b: &mut Bencher) {
    b.iter(|| AccessRights::ADD.to_bytes());
}
#[bench]
fn deserialize_accessrights_add(b: &mut Bencher) {
    let data = AccessRights::ADD.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}
#[bench]
fn serialize_accessrights_read_add(b: &mut Bencher) {
    b.iter(|| AccessRights::READ_ADD.to_bytes());
}
#[bench]
fn deserialize_accessrights_read_add(b: &mut Bencher) {
    let data = AccessRights::READ_ADD.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}
#[bench]
fn serialize_accessrights_read_write(b: &mut Bencher) {
    b.iter(|| AccessRights::READ_WRITE.to_bytes());
}
#[bench]
fn deserialize_accessrights_read_write(b: &mut Bencher) {
    let data = AccessRights::READ_WRITE.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}
#[bench]
fn serialize_accessrights_add_write(b: &mut Bencher) {
    b.iter(|| AccessRights::ADD_WRITE.to_bytes());
}
#[bench]
fn deserialize_accessrights_add_write(b: &mut Bencher) {
    let data = AccessRights::ADD_WRITE.to_bytes().unwrap();
    b.iter(|| AccessRights::from_bytes(&data));
}

fn make_known_urefs() -> BTreeMap<String, Key> {
    let mut urefs = BTreeMap::new();
    urefs.insert("ref1".to_string(), Key::URef([0u8; 32], AccessRights::READ));
    urefs.insert(
        "ref2".to_string(),
        Key::URef([1u8; 32], AccessRights::WRITE),
    );
    urefs.insert("ref3".to_string(), Key::URef([2u8; 32], AccessRights::ADD));
    urefs
}

fn make_contract() -> Contract {
    let known_urefs = make_known_urefs();
    Contract::new(vec![0u8; 1024], known_urefs, 1)
}

fn make_account() -> Account {
    let known_urefs = make_known_urefs();
    Account::new([0u8; 32], 2_635_333_365_164_409_670u64, known_urefs)
}

#[bench]
fn serialize_account(b: &mut Bencher) {
    let account = make_account();

    b.iter(|| ToBytes::to_bytes(black_box(&account)))
}

#[bench]
fn deserialize_account(b: &mut Bencher) {
    let account = make_account();
    let account_bytes = account.clone().to_bytes().unwrap();

    b.iter(|| Account::from_bytes(black_box(&account_bytes)))
}

#[bench]
fn serialize_value_int32(b: &mut Bencher) {
    b.iter(|| Value::Int32(123_456_789i32))
}
#[bench]
fn deserialize_value_int32(b: &mut Bencher) {
    let data = Value::Int32(123_456_789i32).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_uint128(b: &mut Bencher) {
    let value = Value::UInt128(123_456_789u128.into());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_uint128(b: &mut Bencher) {
    let data = Value::UInt128(123_456_789u128.into()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_uint256(b: &mut Bencher) {
    let value = Value::UInt256(123_456_789u64.into());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_uint256(b: &mut Bencher) {
    let data = Value::UInt256(123_456_789u64.into()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_uint512(b: &mut Bencher) {
    let value = Value::UInt512(12_345_679u64.into());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_uint512(b: &mut Bencher) {
    let data = Value::UInt512(12_345_679u64.into()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_bytearray(b: &mut Bencher) {
    let value = Value::ByteArray((0..255).collect());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_bytearray(b: &mut Bencher) {
    let data = Value::ByteArray((0..255).collect()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_listint32(b: &mut Bencher) {
    let value = Value::ListInt32((0..1024).collect());
    b.iter(|| value.to_bytes());
}

#[bench]
fn deserialize_value_listint32(b: &mut Bencher) {
    let data = Value::ListInt32((0..1024).collect()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_string(b: &mut Bencher) {
    let value = Value::String("Hello, world!".to_string());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_string(b: &mut Bencher) {
    let data = Value::String("Hello, world!".to_string())
        .to_bytes()
        .unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_liststring(b: &mut Bencher) {
    let value = Value::ListString(vec!["Hello".to_string(), "World".to_string()]);
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_liststring(b: &mut Bencher) {
    let data = Value::ListString(vec!["Hello".to_string(), "World".to_string()])
        .to_bytes()
        .unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_namedkey(b: &mut Bencher) {
    let value = Value::NamedKey("Key".to_string(), Key::Account([0xffu8; 20]));
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_namedkey(b: &mut Bencher) {
    let value = Value::NamedKey("Key".to_string(), Key::Account([0xffu8; 20]))
        .to_bytes()
        .unwrap();
    b.iter(|| Value::from_bytes(&value));
}

#[bench]
fn serialize_value_account(b: &mut Bencher) {
    let value = Value::Account(make_account());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_account(b: &mut Bencher) {
    let data = Value::Account(make_account()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}
#[bench]
fn serialize_value_contract(b: &mut Bencher) {
    let value = Value::Contract(make_contract());
    b.iter(|| value.to_bytes());
}
#[bench]
fn deserialize_value_contract(b: &mut Bencher) {
    let data = Value::Contract(make_contract()).to_bytes().unwrap();
    b.iter(|| Value::from_bytes(&data));
}

#[bench]
fn serialize_contract(b: &mut Bencher) {
    let contract = make_contract();

    b.iter(|| ToBytes::to_bytes(black_box(&contract)))
}

#[bench]
fn deserialize_contract(b: &mut Bencher) {
    let contract = make_contract();
    let contract_bytes = contract.clone().to_bytes().unwrap();

    b.iter(|| Contract::from_bytes(black_box(&contract_bytes)))
}

#[bench]
fn serialize_u128(b: &mut Bencher) {
    let num_u128 = U128::default();
    b.iter(|| ToBytes::to_bytes(black_box(&num_u128)))
}

#[bench]
fn deserialize_u128(b: &mut Bencher) {
    let num_u128 = U128::default();
    let num_u128_bytes = num_u128.to_bytes().unwrap();

    b.iter(|| U128::from_bytes(black_box(&num_u128_bytes)))
}

#[bench]
fn serialize_u256(b: &mut Bencher) {
    let num_u256 = U256::default();
    b.iter(|| ToBytes::to_bytes(black_box(&num_u256)))
}

#[bench]
fn deserialize_u256(b: &mut Bencher) {
    let num_u256 = U256::default();
    let num_u256_bytes = num_u256.to_bytes().unwrap();

    b.iter(|| U256::from_bytes(black_box(&num_u256_bytes)))
}

#[bench]
fn serialize_u512(b: &mut Bencher) {
    let num_u512 = U512::default();
    b.iter(|| ToBytes::to_bytes(black_box(&num_u512)))
}

#[bench]
fn deserialize_u512(b: &mut Bencher) {
    let num_u512 = U512::default();
    let num_u512_bytes = num_u512.to_bytes().unwrap();

    b.iter(|| U512::from_bytes(black_box(&num_u512_bytes)))
}
