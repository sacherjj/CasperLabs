#![feature(test)]
extern crate common;
extern crate shared;
extern crate storage;
extern crate test;

use test::black_box;
use test::Bencher;

use common::bytesrepr::{FromBytes, ToBytes};
use common::key::Key;
use common::value::Value;
use shared::newtypes::Blake2bHash;
use storage::trie::{Pointer, PointerBlock, Trie};

#[bench]
fn serialize_trie_leaf(b: &mut Bencher) {
    let leaf = Trie::Leaf {
        key: Key::Account([0; 32]),
        value: Value::Int32(42),
    };
    b.iter(|| ToBytes::to_bytes(black_box(&leaf)));
}

#[bench]
fn deserialize_trie_leaf(b: &mut Bencher) {
    let leaf = Trie::Leaf {
        key: Key::Account([0; 32]),
        value: Value::Int32(42),
    };
    let leaf_bytes = leaf.to_bytes().unwrap();
    b.iter(|| u8::from_bytes(black_box(&leaf_bytes)))
}

#[bench]
fn serialize_trie_node(b: &mut Bencher) {
    let node = Trie::<String, String>::Node {
        pointer_block: Box::new(PointerBlock::default()),
    };
    b.iter(|| ToBytes::to_bytes(black_box(&node)));
}

#[bench]
fn deserialize_trie_node(b: &mut Bencher) {
    let node = Trie::<String, String>::Node {
        pointer_block: Box::new(PointerBlock::default()),
    };
    let node_bytes = node.to_bytes().unwrap();

    b.iter(|| u8::from_bytes(black_box(&node_bytes)));
}

#[bench]
fn serialize_trie_node_pointer(b: &mut Bencher) {
    let node = Trie::<String, String>::Extension {
        affix: (0..255).collect(),
        pointer: Pointer::NodePointer(Blake2bHash::new(&[0; 32])),
    };

    b.iter(|| ToBytes::to_bytes(black_box(&node)))
}

#[bench]
fn deserialize_trie_node_pointer(b: &mut Bencher) {
    let node = Trie::<String, String>::Extension {
        affix: (0..255).collect(),
        pointer: Pointer::NodePointer(Blake2bHash::new(&[0; 32])),
    };
    let node_bytes = node.to_bytes().unwrap();

    b.iter(|| u8::from_bytes(black_box(&node_bytes)))
}
