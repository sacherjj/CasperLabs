use contract_ffi::key::Key;
use engine_core::engine_state::{execution_effect::ExecutionEffect, op::Op};

use crate::engine_server::{
    ipc::{
        AddOp as ProtobufAddOp, ExecutionEffect as ProtobufExecutionEffect, NoOp as ProtobufNoOp,
        OpEntry as ProtobufOpEntry, ReadOp as ProtobufReadOp, WriteOp as ProtobufWriteOp,
    },
    transforms::TransformEntry as ProbufTransformEntry,
};

impl From<(Key, Op)> for ProtobufOpEntry {
    fn from((key, op): (Key, Op)) -> ProtobufOpEntry {
        let mut pb_op_entry = ProtobufOpEntry::new();

        pb_op_entry.set_key(key.into());

        match op {
            Op::Read => pb_op_entry.mut_operation().set_read(ProtobufReadOp::new()),
            Op::Write => pb_op_entry
                .mut_operation()
                .set_write(ProtobufWriteOp::new()),
            Op::Add => pb_op_entry.mut_operation().set_add(ProtobufAddOp::new()),
            Op::NoOp => pb_op_entry.mut_operation().set_noop(ProtobufNoOp::new()),
        };

        pb_op_entry
    }
}

impl From<ExecutionEffect> for ProtobufExecutionEffect {
    fn from(execution_effect: ExecutionEffect) -> ProtobufExecutionEffect {
        let mut pb_execution_effect = ProtobufExecutionEffect::new();

        let pb_op_map: Vec<ProtobufOpEntry> =
            execution_effect.ops.into_iter().map(Into::into).collect();
        pb_execution_effect.set_op_map(pb_op_map.into());

        let pb_transform_map: Vec<ProbufTransformEntry> = execution_effect
            .transforms
            .into_iter()
            .map(Into::into)
            .collect();
        pb_execution_effect.set_transform_map(pb_transform_map.into());

        pb_execution_effect
    }
}
