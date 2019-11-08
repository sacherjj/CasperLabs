use std::convert::{TryFrom, TryInto};

use contract_ffi::key::Key;
use engine_shared::{additive_map::AdditiveMap, transform::Transform};

use crate::engine_server::{
    mappings::ParsingError, transforms::TransformEntry as ProtobufTransformEntry,
};

pub struct TransformMap(pub AdditiveMap<Key, Transform>);

impl TryFrom<Vec<ProtobufTransformEntry>> for TransformMap {
    type Error = ParsingError;

    fn try_from(pb_transform_map: Vec<ProtobufTransformEntry>) -> Result<Self, Self::Error> {
        let mut transforms_merged: AdditiveMap<Key, Transform> = AdditiveMap::new();
        for pb_transform_entry in pb_transform_map {
            let (key, transform) = pb_transform_entry.try_into()?;
            transforms_merged.insert_add(key, transform);
        }
        Ok(TransformMap(transforms_merged))
    }
}

#[cfg(test)]
mod tests {
    use contract_ffi::value::Value;

    use super::*;

    #[test]
    fn commit_effects_merges_transforms() {
        // Tests that transforms made to the same key are merged instead of lost.
        let key = Key::Hash([1u8; 32]);
        let setup: Vec<ProtobufTransformEntry> = {
            let transform_entry_first = {
                let mut tmp = ProtobufTransformEntry::new();
                tmp.set_key(key.into());
                tmp.set_transform(Transform::Write(Value::Int32(12)).into());
                tmp
            };
            let transform_entry_second = {
                let mut tmp = ProtobufTransformEntry::new();
                tmp.set_key(key.into());
                tmp.set_transform(Transform::AddInt32(10).into());
                tmp
            };
            vec![transform_entry_first, transform_entry_second]
        };
        let commit: TransformMap = setup
            .try_into()
            .expect("Transforming `Vec<ProtobufTransformEntry>` into `TransformMap` should work.");
        let expected_transform = Transform::Write(contract_ffi::value::Value::Int32(22));
        let commit_transform = commit.0.get(&key);
        assert!(commit_transform.is_some());
        assert_eq!(expected_transform, *commit_transform.unwrap())
    }
}
