use casperlabs_engine_tests::support::test_support::{
    LmdbWasmTestBuilder, WasmTestResult, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::EngineConfig;
use engine_core::engine_state::MAX_PAYMENT;
use engine_storage::global_state::lmdb::LmdbGlobalState;
use std::collections::HashMap;
use std::fs::{self, DirEntry};
use std::io;
use std::path::Path;
use tempfile::TempDir;

const GENESIS_ADDR: [u8; 32] = [1; 32];

fn engine_with_payments() -> EngineConfig {
    EngineConfig::new().set_use_payment_code(true)
}

fn bootstrap(accounts: &[PublicKey]) -> (WasmTestResult<LmdbGlobalState>, TempDir) {
    println!("Creating {} accounts...", accounts.len());
    let accounts_bytes: Vec<Vec<u8>> = accounts
        .iter()
        .map(|public_key| public_key.value().to_vec())
        .collect();
    let amount = U512::from(1);

    let data_dir = TempDir::new().expect("should create temp dir");
    let result = LmdbWasmTestBuilder::new_with_config(&data_dir.path(), engine_with_payments())
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "create_accounts.wasm",
            (accounts_bytes, amount), //args
            DEFAULT_BLOCK_TIME,       // blocktime
            [1; 32],                  // deploy_hash
        )
        .expect_success()
        .commit()
        .finish();

    // println!("Bootstrap finished in {}ms", now.elapsed().as_millis());
    (result, data_dir)
}

fn visit_dirs(dir: &Path, cb: &dyn Fn(&DirEntry)) -> io::Result<()> {
    if dir.is_dir() {
        for entry in fs::read_dir(dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_dir() {
                visit_dirs(&path, cb)?;
            } else {
                cb(&entry);
            }
        }
    }
    Ok(())
}

fn clone_directory(source: &Path) -> TempDir {
    let dest = TempDir::new().expect("should create temp dir");
    visit_dirs(&source, &|dir_entry: &DirEntry| {
        // Source
        let source_path = dir_entry.path();
        // Destination
        let mut dest_path = dest.path().to_path_buf();
        dest_path.push(dir_entry.path().file_name().unwrap());
        assert_ne!(source_path, dest_path);
        fs::copy(source_path, dest_path).unwrap();
    })
    .expect("should visit");
    dest
}

fn exec_send_to_account(builder: &mut LmdbWasmTestBuilder, accounts: &[PublicKey]) {
    let amount: U512 = 1u64.into();
    // To see raw numbers take current time
    for (i, account) in accounts.iter().enumerate() {
        builder
            .exec_with_args(
                GENESIS_ADDR,
                STANDARD_PAYMENT_CONTRACT,
                (U512::from(MAX_PAYMENT),),
                "send_to_account.wasm",
                (*account, amount), //args
                DEFAULT_BLOCK_TIME, // blocktime
                [2 + i as u8; 32],  // deploy_hash
            )
            .expect_success()
            .commit();
    }
    // NOTE: To see raw numbers here calculate tps as following:
    // accounts.len() / now.elapsed().as_millis() * 1000;
}

#[macro_use]
extern crate criterion;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;

use criterion::black_box;
use criterion::Criterion;
use criterion::Throughput;
use std::time::Duration;

pub fn transfer_bench(c: &mut Criterion) {
    let accounts: Vec<PublicKey> = (100u8..=170u8).map(|b| PublicKey::from([b; 32])).collect();
    // Bootstrap database once to shave off time of subsequent bootstrapping
    let (result, source_dir) = bootstrap(&accounts);

    let mut group = c.benchmark_group("tps");

    // Minimize no of samples and measurement times to decrease the total time of this benchmark
    // possibly not decreasung quality of the numbers that much.
    group
        .sample_size(10)
        .nresamples(10)
        .measurement_time(Duration::new(180, 0));

    // Measure by elements where one element/s is one transaction per second
    group.throughput(Throughput::Elements(accounts.len() as u64));
    group.bench_with_input(
        format!("send_to_account/{}", accounts.len()),
        &accounts,
        |b, accounts| {
            // Create new directory with copied contents of existing boostrapped LMDB database
            let cloned_db = clone_directory(&source_dir.path());
            let mut builder =
                LmdbWasmTestBuilder::new_with_config(&cloned_db.path(), engine_with_payments());

            // Applies all properties from existing result
            builder.apply_from_result(&result);

            b.iter(|| exec_send_to_account(black_box(&mut builder), black_box(accounts)))
        },
    );
    group.finish();
}

criterion_group!(benches, transfer_bench);
criterion_main!(benches);
