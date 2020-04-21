use std::{
    collections::BTreeMap,
    mem,
    time::{Duration, Instant},
};

use engine_shared::logging::log_host_function_metrics;

use crate::resolvers::v1_function_index::FunctionIndex;

enum PauseState {
    NotStarted,
    Started(Instant),
    Completed(Duration),
}

impl PauseState {
    fn new() -> Self {
        PauseState::NotStarted
    }

    fn activate(&mut self) {
        match self {
            PauseState::NotStarted => {
                *self = PauseState::Started(Instant::now());
            }
            _ => panic!("PauseState must be NotStarted"),
        }
    }

    fn complete(&mut self) {
        match self {
            PauseState::Started(start) => {
                *self = PauseState::Completed(start.elapsed());
            }
            _ => panic!("Pause must already be active"),
        }
    }

    fn duration(&self) -> Duration {
        match self {
            PauseState::NotStarted => Duration::default(),
            PauseState::Completed(duration) => *duration,
            PauseState::Started(start) => start.elapsed(),
        }
    }
}

pub(super) struct ScopedTimer {
    start: Instant,
    pause_state: PauseState,
    function_index: FunctionIndex,
    properties: BTreeMap<&'static str, String>,
}

impl ScopedTimer {
    pub fn new(function_index: FunctionIndex) -> Self {
        ScopedTimer {
            start: Instant::now(),
            pause_state: PauseState::new(),
            function_index,
            properties: BTreeMap::new(),
        }
    }

    pub fn add_property(&mut self, key: &'static str, value: String) {
        assert!(self.properties.insert(key, value).is_none());
    }

    /// Can be called once only to effectively pause the running timer.  `unpause` can likewise be
    /// called once if the timer has already been paused.
    pub fn pause(&mut self) {
        self.pause_state.activate();
    }

    pub fn unpause(&mut self) {
        self.pause_state.complete();
    }

    fn duration(&self) -> Duration {
        self.start.elapsed() - self.pause_state.duration()
    }
}

impl Drop for ScopedTimer {
    fn drop(&mut self) {
        let host_function = match self.function_index {
            FunctionIndex::GasFuncIndex => return,
            FunctionIndex::WriteFuncIndex => "host_function_write",
            FunctionIndex::WriteLocalFuncIndex => "host_function_write_local",
            FunctionIndex::ReadFuncIndex => "host_function_read_value",
            FunctionIndex::ReadLocalFuncIndex => "host_function_read_value_local",
            FunctionIndex::AddFuncIndex => "host_function_add",
            FunctionIndex::AddLocalFuncIndex => "host_function_add_local",
            FunctionIndex::NewFuncIndex => "host_function_new_uref",
            FunctionIndex::RetFuncIndex => "host_function_ret",
            FunctionIndex::CallContractFuncIndex => "host_function_call_contract",
            FunctionIndex::GetArgFuncIndex => "host_function_get_arg",
            FunctionIndex::GetKeyFuncIndex => "host_function_get_key",
            FunctionIndex::HasKeyFuncIndex => "host_function_has_key",
            FunctionIndex::PutKeyFuncIndex => "host_function_put_key",
            FunctionIndex::StoreFnIndex => "host_function_store_function",
            FunctionIndex::StoreFnAtHashIndex => "host_function_store_function_at_hash",
            FunctionIndex::IsValidURefFnIndex => "host_function_is_valid_uref",
            FunctionIndex::RevertFuncIndex => "host_function_revert",
            FunctionIndex::AddAssociatedKeyFuncIndex => "host_function_add_associated_key",
            FunctionIndex::RemoveAssociatedKeyFuncIndex => "host_function_remove_associated_key",
            FunctionIndex::UpdateAssociatedKeyFuncIndex => "host_function_update_associated_key",
            FunctionIndex::SetActionThresholdFuncIndex => "host_function_set_action_threshold",
            FunctionIndex::LoadNamedKeysFuncIndex => "host_function_load_named_keys",
            FunctionIndex::RemoveKeyFuncIndex => "host_function_remove_key",
            FunctionIndex::GetCallerIndex => "host_function_get_caller",
            FunctionIndex::GetBlocktimeIndex => "host_function_get_blocktime",
            FunctionIndex::CreatePurseIndex => "host_function_create_purse",
            FunctionIndex::TransferToAccountIndex => "host_function_transfer_to_account",
            FunctionIndex::TransferFromPurseToAccountIndex => {
                "host_function_transfer_from_purse_to_account"
            }
            FunctionIndex::TransferFromPurseToPurseIndex => {
                "host_function_transfer_from_purse_to_purse"
            }
            FunctionIndex::GetBalanceIndex => "host_function_get_balance",
            FunctionIndex::GetPhaseIndex => "host_function_get_phase",
            FunctionIndex::UpgradeContractAtURefIndex => "host_function_upgrade_contract_at_uref",
            FunctionIndex::GetSystemContractIndex => "host_function_get_system_contract",
            FunctionIndex::GetMainPurseIndex => "host_function_get_main_purse",
            FunctionIndex::GetArgSizeFuncIndex => "host_function_get_arg_size",
            FunctionIndex::ReadHostBufferIndex => "host_function_read_host_buffer",
            #[cfg(feature = "test-support")]
            FunctionIndex::PrintIndex => "host_function_print",
        };

        let mut properties = mem::take(&mut self.properties);
        properties.insert(
            "duration_in_seconds",
            format!("{:.06e}", self.duration().as_secs_f64()),
        );

        log_host_function_metrics(host_function, properties);
    }
}
