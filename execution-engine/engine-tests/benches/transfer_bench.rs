use criterion::{criterion_group, criterion_main, Criterion, Throughput};
use tempfile::TempDir;

use engine_core::engine_state::EngineConfig;
use engine_storage::global_state::lmdb::LmdbGlobalState;
use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, LmdbWasmTestBuilder, WasmTestResult,
    DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};
use types::{
    account::{PublicKey, PurseId},
    Key, U512,
};

const CONTRACT_CREATE_ACCOUNTS: &str = "create_accounts.wasm";
const CONTRACT_CREATE_PURSES: &str = "create_purses.wasm";
const CONTRACT_TRANSFER_TO_EXISTING_ACCOUNT: &str = "transfer_to_existing_account.wasm";
const CONTRACT_TRANSFER_TO_PURSE: &str = "transfer_to_purse.wasm";

/// Size of batch used in multiple execs benchmark, and multiple deploys per exec cases.
const TRANSFER_BATCH_SIZE: u64 = 3;
const PER_RUN_FUNDING: u64 = 10_000_000;
const TARGET_ADDR: [u8; 32] = [127; 32];

/// Converts an integer into an array of type [u8; 32] by converting integer
/// into its big endian representation and embedding it at the end of the
/// range.
fn make_deploy_hash(i: u64) -> [u8; 32] {
    let mut result = [128; 32];
    result[32 - 8..].copy_from_slice(&i.to_be_bytes());
    result
}

fn bootstrap(accounts: &[PublicKey], amount: U512) -> (WasmTestResult<LmdbGlobalState>, TempDir) {
    let accounts_bytes: Vec<Vec<u8>> = accounts
        .iter()
        .map(|public_key| public_key.value().to_vec())
        .collect();

    let data_dir = TempDir::new().expect("should create temp dir");

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_CREATE_ACCOUNTS,
        (accounts_bytes, amount),
    )
    .build();

    let result = LmdbWasmTestBuilder::new_with_config(&data_dir.path(), EngineConfig::new())
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit()
        .finish();

    (result, data_dir)
}

fn create_purses(
    builder: &mut LmdbWasmTestBuilder,
    source: [u8; 32],
    total_purses: u64,
    purse_amount: U512,
) -> Vec<PurseId> {
    let exec_request = ExecuteRequestBuilder::standard(
        source,
        CONTRACT_CREATE_PURSES,
        (total_purses, purse_amount),
    )
    .build();

    builder.exec(exec_request).expect_success().commit();

    // Return creates purses for given account by filtering named keys
    let query_result = builder
        .query(None, Key::Account(source), &[])
        .expect("should query target");
    let account = query_result
        .as_account()
        .unwrap_or_else(|| panic!("result should be account but received {:?}", query_result));

    (0..total_purses)
        .map(|index| {
            let purse_lookup_key = format!("purse:{}", index);
            let purse_uref = account
                .named_keys()
                .get(&purse_lookup_key)
                .and_then(Key::as_uref)
                .unwrap_or_else(|| panic!("should get named key {} as uref", purse_lookup_key));
            PurseId::new(*purse_uref)
        })
        .collect()
}

/// Uses multiple exec requests with a single deploy to transfer tokens. Executes all transfers in
/// batch determined by value of TRANSFER_BATCH_SIZE.
fn transfer_to_account_multiple_execs(builder: &mut LmdbWasmTestBuilder, account: PublicKey) {
    let amount = U512::one();

    for _ in 0..TRANSFER_BATCH_SIZE {
        let exec_request = ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            CONTRACT_TRANSFER_TO_EXISTING_ACCOUNT,
            (account, amount),
        )
        .build();
        builder.exec(exec_request).expect_success().commit();
    }
}

/// Executes multiple deploys per single exec with based on TRANSFER_BATCH_SIZE.
fn transfer_to_account_multiple_deploys(builder: &mut LmdbWasmTestBuilder, account: PublicKey) {
    let mut exec_builder = ExecuteRequestBuilder::new();

    for i in 0..TRANSFER_BATCH_SIZE {
        let deploy = DeployItemBuilder::default()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(PER_RUN_FUNDING),))
            .with_session_code(
                CONTRACT_TRANSFER_TO_EXISTING_ACCOUNT,
                (account, U512::one()),
            )
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .with_deploy_hash(make_deploy_hash(i)) // deploy_hash
            .build();
        exec_builder = exec_builder.push_deploy(deploy);
    }

    builder.exec(exec_builder.build()).expect_success().commit();
}

/// Uses multiple exec requests with a single deploy to transfer tokens from purse to purse.
/// Executes all transfers in batch determined by value of TRANSFER_BATCH_SIZE.
fn transfer_to_purse_multiple_execs(builder: &mut LmdbWasmTestBuilder, purse_id: PurseId) {
    let amount = U512::one();

    for _ in 0..TRANSFER_BATCH_SIZE {
        let exec_request = ExecuteRequestBuilder::standard(
            TARGET_ADDR,
            CONTRACT_TRANSFER_TO_PURSE,
            (purse_id, amount),
        )
        .build();
        builder.exec(exec_request).expect_success().commit();
    }
}

/// Executes multiple deploys per single exec with based on TRANSFER_BATCH_SIZE.
fn transfer_to_purse_multiple_deploys(builder: &mut LmdbWasmTestBuilder, purse_id: PurseId) {
    let mut exec_builder = ExecuteRequestBuilder::new();

    for i in 0..TRANSFER_BATCH_SIZE {
        let deploy = DeployItemBuilder::default()
            .with_address(TARGET_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(PER_RUN_FUNDING),))
            .with_session_code(CONTRACT_TRANSFER_TO_PURSE, (purse_id, U512::one()))
            .with_authorization_keys(&[PublicKey::new(TARGET_ADDR)])
            .with_deploy_hash(make_deploy_hash(i)) // deploy_hash
            .build();
        exec_builder = exec_builder.push_deploy(deploy);
    }

    builder.exec(exec_builder.build()).expect_success().commit();
}

pub fn transfer_bench(c: &mut Criterion) {
    let target_account = PublicKey::new(TARGET_ADDR);
    let bootstrap_accounts = vec![target_account];

    let mut group = c.benchmark_group("tps");

    // Minimize no of samples and measurement times to decrease the total time of this benchmark
    // possibly not decreasing quality of the numbers that much.
    group.sample_size(10);

    // Measure by elements where one element/s is one transaction per second
    group.throughput(Throughput::Elements(TRANSFER_BATCH_SIZE));

    // Bootstrap database once
    let (result_1, _source_dir_1) = bootstrap(&bootstrap_accounts, U512::one());
    let mut builder_1 = LmdbWasmTestBuilder::from_result(result_1);

    //
    // Transfers to existing account
    //

    group.bench_function(
        format!(
            "transfer_to_existing_account_multiple_execs/{}",
            TRANSFER_BATCH_SIZE
        ),
        |b| {
            b.iter(|| {
                // Execute multiple deploys with multiple exec requests
                transfer_to_account_multiple_execs(&mut builder_1, target_account)
            })
        },
    );

    let (result_2, _source_dir_2) = bootstrap(&bootstrap_accounts, U512::one());
    let mut builder_2 = LmdbWasmTestBuilder::from_result(result_2);

    group.bench_function(
        format!(
            "transfer_to_existing_account_multiple_deploys_per_exec/{}",
            TRANSFER_BATCH_SIZE
        ),
        |b| {
            b.iter(|| {
                // Execute multiple deploys with a single exec request
                transfer_to_account_multiple_deploys(&mut builder_2, target_account)
            })
        },
    );

    //
    // Transfers to purse
    //

    let (result_3, _source_dir_3) = bootstrap(&bootstrap_accounts, *DEFAULT_PAYMENT * 10);
    let mut builder_3 = LmdbWasmTestBuilder::from_result(result_3);

    let purses_1 = create_purses(&mut builder_3, TARGET_ADDR, 1, U512::one());

    group.bench_function(
        format!("transfer_to_purse_multiple_execs/{}", TRANSFER_BATCH_SIZE),
        |b| {
            let target_purse = purses_1[0];
            b.iter(|| {
                // Execute multiple deploys with mutliple exec request
                transfer_to_purse_multiple_execs(&mut builder_3, target_purse)
            })
        },
    );

    let (result_4, _source_dir_4) = bootstrap(&bootstrap_accounts, *DEFAULT_PAYMENT * 10);
    let mut builder_4 = LmdbWasmTestBuilder::from_result(result_4);

    let purses_2 = create_purses(&mut builder_4, TARGET_ADDR, 1, U512::one());

    group.bench_function(
        format!(
            "transfer_to_purse_multiple_deploys_per_exec/{}",
            TRANSFER_BATCH_SIZE
        ),
        |b| {
            let target_purse = purses_2[0];
            b.iter(|| {
                // Execute multiple deploys with a single exec request
                transfer_to_purse_multiple_deploys(&mut builder_4, target_purse)
            })
        },
    );

    group.finish();
}

criterion_group!(benches, transfer_bench);
criterion_main!(benches);
