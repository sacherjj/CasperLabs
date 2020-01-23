use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    iter::{self, FromIterator},
    rc::Rc,
};

use rand::RngCore;

use engine_shared::{
    account::{Account, AssociatedKeys},
    additive_map::AdditiveMap,
    contract::Contract,
    gas::Gas,
    newtypes::CorrelationId,
    stored_value::StoredValue,
    transform::Transform,
};
use engine_storage::global_state::{
    in_memory::{InMemoryGlobalState, InMemoryGlobalStateView},
    CommitResult, StateProvider,
};
use types::{
    account::{
        ActionType, AddKeyFailure, PublicKey, PurseId, RemoveKeyFailure, SetThresholdFailure,
        Weight,
    },
    AccessRights, BlockTime, CLValue, Key, Phase, ProtocolVersion, URef, LOCAL_SEED_LENGTH,
};

use super::{attenuate_uref_for_account, Address, Error, RuntimeContext};
use crate::{
    engine_state::SYSTEM_ACCOUNT_ADDR,
    execution::{extract_access_rights_from_keys, AddressGenerator},
    tracking_copy::TrackingCopy,
};

const DEPLOY_HASH: [u8; 32] = [1u8; 32];
const PHASE: Phase = Phase::Session;

fn mock_tc(init_key: Key, init_account: Account) -> TrackingCopy<InMemoryGlobalStateView> {
    let correlation_id = CorrelationId::new();
    let hist = InMemoryGlobalState::empty().unwrap();
    let root_hash = hist.empty_root_hash;
    let transform = Transform::Write(StoredValue::Account(init_account));

    let mut m = AdditiveMap::new();
    m.insert(init_key, transform);
    let commit_result = hist
        .commit(correlation_id, root_hash, m)
        .expect("Creation of mocked account should be a success.");

    let new_hash = match commit_result {
        CommitResult::Success { state_root, .. } => state_root,
        other => panic!("Commiting changes to test History failed: {:?}.", other),
    };

    let reader = hist
        .checkout(new_hash)
        .expect("Checkout should not throw errors.")
        .expect("Root hash should exist.");

    TrackingCopy::new(reader)
}

fn mock_account_with_purse_id(addr: [u8; 32], purse_id: [u8; 32]) -> (Key, Account) {
    let associated_keys = AssociatedKeys::new(PublicKey::new(addr), Weight::new(1));
    let account = Account::new(
        addr,
        BTreeMap::new(),
        PurseId::new(URef::new(purse_id, AccessRights::READ_ADD_WRITE)),
        associated_keys,
        Default::default(),
    );
    let key = Key::Account(addr);

    (key, account)
}

fn mock_account(addr: [u8; 32]) -> (Key, Account) {
    mock_account_with_purse_id(addr, [0; 32])
}

// create random account key.
fn random_account_key<G: RngCore>(entropy_source: &mut G) -> Key {
    let mut key = [0u8; 32];
    entropy_source.fill_bytes(&mut key);
    Key::Account(key)
}

// create random contract key.
fn random_contract_key<G: RngCore>(entropy_source: &mut G) -> Key {
    let mut key = [0u8; 32];
    entropy_source.fill_bytes(&mut key);
    Key::Hash(key)
}

// Create URef Key.
fn create_uref(address_generator: &mut AddressGenerator, rights: AccessRights) -> Key {
    let address = address_generator.create_address();
    Key::URef(URef::new(address, rights))
}

fn random_local_key<G: RngCore>(entropy_source: &mut G, seed: [u8; LOCAL_SEED_LENGTH]) -> Key {
    let mut key = [0u8; 64];
    entropy_source.fill_bytes(&mut key);
    Key::local(seed, &key)
}

fn mock_runtime_context<'a>(
    account: &'a Account,
    base_key: Key,
    named_keys: &'a mut BTreeMap<String, Key>,
    access_rights: HashMap<Address, HashSet<AccessRights>>,
    address_generator: AddressGenerator,
) -> RuntimeContext<'a, InMemoryGlobalStateView> {
    let tc = mock_tc(base_key, account.clone());
    RuntimeContext::new(
        Rc::new(RefCell::new(tc)),
        named_keys,
        access_rights,
        Vec::new(),
        BTreeSet::from_iter(vec![PublicKey::new([0; 32])]),
        &account,
        base_key,
        BlockTime::new(0),
        [1u8; 32],
        Gas::default(),
        Gas::default(),
        0,
        Rc::new(RefCell::new(address_generator)),
        ProtocolVersion::V1_0_0,
        CorrelationId::new(),
        Phase::Session,
        Default::default(),
    )
}

#[allow(clippy::assertions_on_constants)]
fn assert_forged_reference<T>(result: Result<T, Error>) {
    match result {
        Err(Error::ForgedReference(_)) => assert!(true),
        _ => panic!("Error. Test should have failed with ForgedReference error but didn't."),
    }
}

#[allow(clippy::assertions_on_constants)]
fn assert_invalid_access<T: std::fmt::Debug>(result: Result<T, Error>, expecting: AccessRights) {
    match result {
        Err(Error::InvalidAccess { required }) if required == expecting => assert!(true),
        other => panic!(
            "Error. Test should have failed with InvalidAccess error but didn't: {:?}.",
            other
        ),
    }
}

fn test<T, F>(access_rights: HashMap<Address, HashSet<AccessRights>>, query: F) -> Result<T, Error>
where
    F: FnOnce(RuntimeContext<InMemoryGlobalStateView>) -> Result<T, Error>,
{
    let base_acc_addr = [0u8; 32];
    let deploy_hash = [1u8; 32];
    let (key, account) = mock_account(base_acc_addr);
    let mut uref_map = BTreeMap::new();
    let address_generator = AddressGenerator::new(deploy_hash, Phase::Session);
    let runtime_context = mock_runtime_context(
        &account,
        key,
        &mut uref_map,
        access_rights,
        address_generator,
    );
    query(runtime_context)
}

#[test]
fn use_uref_valid() {
    // Test fixture
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref]);
    // Use uref as the key to perform an action on the global state.
    // This should succeed because the uref is valid.
    let value = StoredValue::CLValue(CLValue::from_t(43_i32).unwrap());
    let query_result = test(access_rights, |mut rc| rc.write_gs(uref, value));
    query_result.expect("writing using valid uref should succeed");
}

#[test]
fn use_uref_forged() {
    // Test fixture
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let access_rights = HashMap::new();
    let value = StoredValue::CLValue(CLValue::from_t(43_i32).unwrap());
    let query_result = test(access_rights, |mut rc| rc.write_gs(uref, value));

    assert_forged_reference(query_result);
}

#[test]
fn store_contract_with_uref_valid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref]);

    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        iter::once(("ValidURef".to_owned(), uref)).collect(),
        ProtocolVersion::V1_0_0,
    ));

    let query_result = test(access_rights, |mut rc| {
        let contract_addr = rc
            .store_function_at_hash(contract.clone())
            .expect("Storing contract with valid URefs should succeed.");
        let contract_key = Key::Hash(contract_addr);
        rc.read_gs(&contract_key)
    });

    let contract_gs = query_result
        .expect("Reading contract from the GS should work.")
        .expect("Contract should be found.");

    assert_eq!(contract, contract_gs);
}

#[test]
fn store_contract_with_uref_forged() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        iter::once(("ForgedURef".to_owned(), uref)).collect(),
        ProtocolVersion::V1_0_0,
    ));

    let query_result = test(HashMap::new(), |mut rc| {
        rc.store_function_at_hash(contract.clone())
    });

    assert_forged_reference(query_result);
}

#[test]
fn store_contract_under_uref_valid() {
    // Test that storing contract under URef that is known and has WRITE access
    // works.
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let contract_uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let access_rights = extract_access_rights_from_keys(vec![contract_uref]);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        iter::once(("ValidURef".to_owned(), contract_uref)).collect(),
        ProtocolVersion::V1_0_0,
    ));

    let query_result = test(access_rights, |mut rc| {
        rc.write_gs(contract_uref, contract.clone())
            .expect("Storing contract under known and writeable URef should work.");
        rc.read_gs(&contract_uref)
    });

    let contract_gs = query_result
        .expect("Reading contract from the GS should work.")
        .expect("Contract should be found.");

    assert_eq!(contract, contract_gs);
}

#[test]
fn store_contract_under_uref_forged() {
    // Test that storing contract under URef that is not known fails with
    // ForgedReference error.
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let contract_uref = create_uref(&mut rng, AccessRights::READ_WRITE);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        BTreeMap::new(),
        ProtocolVersion::V1_0_0,
    ));

    let query_result = test(HashMap::new(), |mut rc| {
        rc.write_gs(contract_uref, contract.clone())
    });

    assert_forged_reference(query_result);
}

#[test]
fn store_contract_uref_invalid_access() {
    // Test that storing contract under URef that is known but is not writeable
    // fails.
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let contract_uref = create_uref(&mut rng, AccessRights::READ);
    let access_rights = extract_access_rights_from_keys(vec![contract_uref]);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        BTreeMap::new(),
        ProtocolVersion::V1_0_0,
    ));

    let query_result = test(access_rights, |mut rc| {
        rc.write_gs(contract_uref, contract.clone())
    });

    assert_invalid_access(query_result, AccessRights::WRITE);
}

#[test]
fn account_key_not_writeable() {
    let mut rng = rand::thread_rng();
    let acc_key = random_account_key(&mut rng);
    let query_result = test(HashMap::new(), |mut rc| {
        rc.write_gs(
            acc_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });
    assert_invalid_access(query_result, AccessRights::WRITE);
}

#[test]
fn account_key_readable_valid() {
    // Account key is readable if it is a "base" key - current context of the
    // execution.
    let query_result = test(HashMap::new(), |mut rc| {
        let base_key = rc.base_key();

        let result = rc
            .read_gs(&base_key)
            .expect("Account key is readable.")
            .expect("Account is found in GS.");

        assert_eq!(result, StoredValue::Account(rc.account().clone()));
        Ok(())
    });

    assert!(query_result.is_ok());
}

#[test]
fn account_key_readable_invalid() {
    // Account key is NOT readable if it is different than the "base" key.
    let mut rng = rand::thread_rng();
    let other_acc_key = random_account_key(&mut rng);

    let query_result = test(HashMap::new(), |mut rc| rc.read_gs(&other_acc_key));

    assert_invalid_access(query_result, AccessRights::READ);
}

#[test]
fn account_key_addable_valid() {
    // Account key is addable if it is a "base" key - current context of the
    // execution.
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref = create_uref(&mut rng, AccessRights::READ);
    let access_rights = extract_access_rights_from_keys(vec![uref]);
    let query_result = test(access_rights, |mut rc| {
        let base_key = rc.base_key();
        let uref_name = "NewURef".to_owned();
        let named_key = StoredValue::CLValue(CLValue::from_t((uref_name.clone(), uref)).unwrap());

        rc.add_gs(base_key, named_key).expect("Adding should work.");

        let named_key_transform = Transform::AddKeys(iter::once((uref_name, uref)).collect());

        assert_eq!(
            *rc.effect().transforms.get(&base_key).unwrap(),
            named_key_transform
        );

        Ok(())
    });

    assert!(query_result.is_ok());
}

#[test]
fn account_key_addable_invalid() {
    // Account key is NOT addable if it is a "base" key - current context of the
    // execution.
    let mut rng = rand::thread_rng();
    let other_acc_key = random_account_key(&mut rng);

    let query_result = test(HashMap::new(), |mut rc| {
        rc.add_gs(
            other_acc_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });

    assert_invalid_access(query_result, AccessRights::ADD);
}

#[test]
fn contract_key_readable_valid() {
    // Account key is readable if it is a "base" key - current context of the
    // execution.
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let query_result = test(HashMap::new(), |mut rc| rc.read_gs(&contract_key));

    assert!(query_result.is_ok());
}

#[test]
fn contract_key_not_writeable() {
    // Account key is readable if it is a "base" key - current context of the
    // execution.
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let query_result = test(HashMap::new(), |mut rc| {
        rc.write_gs(
            contract_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });

    assert_invalid_access(query_result, AccessRights::WRITE);
}

#[test]
fn contract_key_addable_valid() {
    // Contract key is addable if it is a "base" key - current context of the
    // execution.
    let base_acc_addr = [0u8; 32];
    let (account_key, account) = mock_account(base_acc_addr);
    let mut address_generator = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        BTreeMap::new(),
        ProtocolVersion::V1_0_0,
    ));
    let tc = Rc::new(RefCell::new(mock_tc(account_key, account.clone())));
    // Store contract in the GlobalState so that we can mainpulate it later.
    tc.borrow_mut().write(contract_key, contract);

    let mut uref_map = BTreeMap::new();
    let uref = create_uref(&mut address_generator, AccessRights::WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref]);

    let mut runtime_context = RuntimeContext::new(
        Rc::clone(&tc),
        &mut uref_map,
        access_rights,
        Vec::new(),
        BTreeSet::from_iter(vec![PublicKey::new(base_acc_addr)]),
        &account,
        contract_key,
        BlockTime::new(0),
        DEPLOY_HASH,
        Gas::default(),
        Gas::default(),
        0,
        Rc::new(RefCell::new(address_generator)),
        ProtocolVersion::V1_0_0,
        CorrelationId::new(),
        PHASE,
        Default::default(),
    );

    let uref_name = "NewURef".to_owned();
    let named_key = StoredValue::CLValue(CLValue::from_t((uref_name.clone(), uref)).unwrap());

    runtime_context
        .add_gs(contract_key, named_key)
        .expect("Adding should work.");

    let updated_contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        iter::once((uref_name, uref)).collect(),
        ProtocolVersion::V1_0_0,
    ));

    assert_eq!(
        *tc.borrow().effect().transforms.get(&contract_key).unwrap(),
        Transform::Write(updated_contract)
    );
}

#[test]
fn contract_key_addable_invalid() {
    // Contract key is addable if it is a "base" key - current context of the
    // execution.
    let base_acc_addr = [0u8; 32];
    let (account_key, account) = mock_account(base_acc_addr);
    let mut address_generator = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let other_contract_key = random_contract_key(&mut rng);
    let contract = StoredValue::Contract(Contract::new(
        Vec::new(),
        BTreeMap::new(),
        ProtocolVersion::V1_0_0,
    ));
    let tc = Rc::new(RefCell::new(mock_tc(account_key, account.clone())));
    // Store contract in the GlobalState so that we can mainpulate it later.
    tc.borrow_mut().write(contract_key, contract);

    let mut uref_map = BTreeMap::new();
    let uref = create_uref(&mut address_generator, AccessRights::WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref]);
    let mut runtime_context = RuntimeContext::new(
        Rc::clone(&tc),
        &mut uref_map,
        access_rights,
        Vec::new(),
        BTreeSet::from_iter(vec![PublicKey::new(base_acc_addr)]),
        &account,
        other_contract_key,
        BlockTime::new(0),
        DEPLOY_HASH,
        Gas::default(),
        Gas::default(),
        0,
        Rc::new(RefCell::new(address_generator)),
        ProtocolVersion::V1_0_0,
        CorrelationId::new(),
        PHASE,
        Default::default(),
    );

    let uref_name = "NewURef".to_owned();
    let named_key = StoredValue::CLValue(CLValue::from_t((uref_name, uref)).unwrap());

    let result = runtime_context.add_gs(contract_key, named_key);

    assert_invalid_access(result, AccessRights::ADD);
}

#[test]
fn uref_key_readable_valid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::READ);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| rc.read_gs(&uref_key));
    assert!(query_result.is_ok());
}

#[test]
fn uref_key_readable_invalid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| rc.read_gs(&uref_key));
    assert_invalid_access(query_result, AccessRights::READ);
}

#[test]
fn uref_key_writeable_valid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| {
        rc.write_gs(
            uref_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });
    assert!(query_result.is_ok());
}

#[test]
fn uref_key_writeable_invalid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::READ);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| {
        rc.write_gs(
            uref_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });
    assert_invalid_access(query_result, AccessRights::WRITE);
}

#[test]
fn uref_key_addable_valid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::ADD_WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| {
        rc.write_gs(
            uref_key,
            StoredValue::CLValue(CLValue::from_t(10_i32).unwrap()),
        )
        .expect("Writing to the GlobalState should work.");
        rc.add_gs(
            uref_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });
    assert!(query_result.is_ok());
}

#[test]
fn uref_key_addable_invalid() {
    let mut rng = AddressGenerator::new(DEPLOY_HASH, PHASE);
    let uref_key = create_uref(&mut rng, AccessRights::WRITE);
    let access_rights = extract_access_rights_from_keys(vec![uref_key]);
    let query_result = test(access_rights, |mut rc| {
        rc.add_gs(
            uref_key,
            StoredValue::CLValue(CLValue::from_t(1_i32).unwrap()),
        )
    });
    assert_invalid_access(query_result, AccessRights::ADD);
}

#[test]
fn local_key_writeable_valid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = runtime_context.seed();
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_writeable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn local_key_writeable_invalid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = [1u8; LOCAL_SEED_LENGTH];
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_writeable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn local_key_readable_valid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = runtime_context.seed();
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_readable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn local_key_readable_invalid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = [1u8; LOCAL_SEED_LENGTH];
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_readable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn local_key_addable_valid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = runtime_context.seed();
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_addable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn local_key_addable_invalid() {
    let access_rights = HashMap::new();
    let query = |runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let mut rng = rand::thread_rng();
        let seed = [1u8; LOCAL_SEED_LENGTH];
        let key = random_local_key(&mut rng, seed);
        runtime_context.validate_addable(&key)
    };
    let query_result = test(access_rights, query);
    assert!(query_result.is_err())
}

#[test]
fn manage_associated_keys() {
    // Testing a valid case only - successfuly added a key, and successfuly removed,
    // making sure `account_dirty` mutated
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let public_key = PublicKey::new([42; 32]);
        let weight = Weight::new(155);

        // Add a key (this doesn't check for all invariants as `add_key`
        // is already tested in different place)
        runtime_context
            .add_associated_key(public_key, weight)
            .expect("Unable to add key");

        let effect = runtime_context.effect();
        let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
        let account = match transform {
            Transform::Write(StoredValue::Account(account)) => account,
            _ => panic!("Invalid transform operation found"),
        };
        account
            .get_associated_key_weight(public_key)
            .expect("Public key wasn't added to associated keys");

        let new_weight = Weight::new(100);
        runtime_context
            .update_associated_key(public_key, new_weight)
            .expect("Unable to update key");

        let effect = runtime_context.effect();
        let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
        let account = match transform {
            Transform::Write(StoredValue::Account(account)) => account,
            _ => panic!("Invalid transform operation found"),
        };
        let value = account
            .get_associated_key_weight(public_key)
            .expect("Public key wasn't added to associated keys");

        assert_eq!(value, &new_weight, "value was not updated");

        // Remove a key that was already added
        runtime_context
            .remove_associated_key(public_key)
            .expect("Unable to remove key");

        // Verify
        let effect = runtime_context.effect();
        let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
        let account = match transform {
            Transform::Write(StoredValue::Account(account)) => account,
            _ => panic!("Invalid transform operation found"),
        };

        assert!(account.get_associated_key_weight(public_key).is_none());

        // Remove a key that was already removed
        runtime_context
            .remove_associated_key(public_key)
            .expect_err("A non existing key was unexpectedly removed again");

        Ok(())
    };
    let _ = test(access_rights, query);
}

#[test]
fn action_thresholds_management() {
    // Testing a valid case only - successfuly added a key, and successfuly removed,
    // making sure `account_dirty` mutated
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        runtime_context
            .add_associated_key(PublicKey::new([42; 32]), Weight::new(254))
            .expect("Unable to add associated key with maximum weight");
        runtime_context
            .set_action_threshold(ActionType::KeyManagement, Weight::new(253))
            .expect("Unable to set action threshold KeyManagement");
        runtime_context
            .set_action_threshold(ActionType::Deployment, Weight::new(252))
            .expect("Unable to set action threshold Deployment");

        let effect = runtime_context.effect();
        let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
        let mutated_account = match transform {
            Transform::Write(StoredValue::Account(account)) => account,
            _ => panic!("Invalid transform operation found"),
        };

        assert_eq!(
            mutated_account.action_thresholds().deployment(),
            &Weight::new(252)
        );
        assert_eq!(
            mutated_account.action_thresholds().key_management(),
            &Weight::new(253)
        );

        runtime_context
            .set_action_threshold(ActionType::Deployment, Weight::new(255))
            .expect_err("Shouldn't be able to set deployment threshold higher than key management");

        Ok(())
    };
    let _ = test(access_rights, query);
}

#[test]
fn should_verify_ownership_before_adding_key() {
    // Testing a valid case only - successfuly added a key, and successfuly removed,
    // making sure `account_dirty` mutated
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        // Overwrites a `base_key` to a different one before doing any operation as
        // account `[0; 32]`
        runtime_context.base_key = Key::Hash([1; 32]);

        let err = runtime_context
            .add_associated_key(PublicKey::new([84; 32]), Weight::new(123))
            .expect_err("This operation should return error");

        match err {
            Error::AddKeyFailure(AddKeyFailure::PermissionDenied) => {}
            e => panic!("Invalid error variant: {:?}", e),
        }

        Ok(())
    };
    let _ = test(access_rights, query);
}

#[test]
fn should_verify_ownership_before_removing_a_key() {
    // Testing a valid case only - successfuly added a key, and successfuly removed,
    // making sure `account_dirty` mutated
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        // Overwrites a `base_key` to a different one before doing any operation as
        // account `[0; 32]`
        runtime_context.base_key = Key::Hash([1; 32]);

        let err = runtime_context
            .remove_associated_key(PublicKey::new([84; 32]))
            .expect_err("This operation should return error");

        match err {
            Error::RemoveKeyFailure(RemoveKeyFailure::PermissionDenied) => {}
            ref e => panic!("Invalid error variant: {:?}", e),
        }

        Ok(())
    };
    let _ = test(access_rights, query);
}

#[test]
fn should_verify_ownership_before_setting_action_threshold() {
    // Testing a valid case only - successfuly added a key, and successfuly removed,
    // making sure `account_dirty` mutated
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        // Overwrites a `base_key` to a different one before doing any operation as
        // account `[0; 32]`
        runtime_context.base_key = Key::Hash([1; 32]);

        let err = runtime_context
            .set_action_threshold(ActionType::Deployment, Weight::new(123))
            .expect_err("This operation should return error");

        match err {
            Error::SetThresholdFailure(SetThresholdFailure::PermissionDeniedError) => {}
            ref e => panic!("Invalid error variant: {:?}", e),
        }

        Ok(())
    };
    let _ = test(access_rights, query);
}

#[test]
fn can_roundtrip_key_value_pairs_into_local_state() {
    let access_rights = HashMap::new();
    let query = |mut runtime_context: RuntimeContext<InMemoryGlobalStateView>| {
        let test_key = b"test_key";
        let test_value = CLValue::from_t("test_value".to_string()).unwrap();

        runtime_context
            .write_ls(test_key, test_value.clone())
            .expect("should write_ls");

        let result = runtime_context.read_ls(test_key).expect("should read_ls");

        Ok(result == Some(test_value))
    };
    let query_result = test(access_rights, query).expect("should be ok");
    assert!(query_result)
}

#[test]
fn remove_uref_works() {
    // Test that `remove_uref` removes Key from both ephemeral representation
    // which is one of the current RuntimeContext, and also puts that change
    // into the `TrackingCopy` so that it's later committed to the GlobalState.

    let named_keys = HashMap::new();
    let base_acc_addr = [0u8; 32];
    let deploy_hash = [1u8; 32];
    let (key, account) = mock_account(base_acc_addr);
    let mut address_generator = AddressGenerator::new(deploy_hash, Phase::Session);
    let uref_name = "Foo".to_owned();
    let uref_key = create_uref(&mut address_generator, AccessRights::READ);
    let mut uref_map = iter::once((uref_name.clone(), uref_key)).collect();
    let mut runtime_context =
        mock_runtime_context(&account, key, &mut uref_map, named_keys, address_generator);

    assert!(runtime_context.named_keys_contains_key(&uref_name));
    assert!(runtime_context.remove_key(&uref_name).is_ok());
    assert!(runtime_context.validate_key(&uref_key).is_err());
    assert!(!runtime_context.named_keys_contains_key(&uref_name));
    let effects = runtime_context.effect();
    let transform = effects.transforms.get(&key).unwrap();
    let account = match transform {
        Transform::Write(StoredValue::Account(account)) => account,
        _ => panic!("Invalid transform operation found"),
    };
    assert!(!account.named_keys().contains_key(&uref_name));
}

#[test]
fn validate_valid_purse_id_of_an_account() {
    // Tests that URef which matches a purse_id of a given context gets validated
    let mock_purse_id = [42u8; 32];
    let named_keys = HashMap::new();
    let base_acc_addr = [0u8; 32];
    let deploy_hash = [1u8; 32];
    let (key, account) = mock_account_with_purse_id(base_acc_addr, mock_purse_id);
    let address_generator = AddressGenerator::new(deploy_hash, Phase::Session);
    let mut uref_map = BTreeMap::new();
    let runtime_context =
        mock_runtime_context(&account, key, &mut uref_map, named_keys, address_generator);

    // URef that has the same id as purse_id of an account gets validated
    // successfully.
    let purse_id = URef::new(mock_purse_id, AccessRights::READ_ADD_WRITE);
    assert!(runtime_context.validate_uref(&purse_id).is_ok());

    // URef that has the same id as purse_id of an account gets validated
    // successfully as the passed purse has only subset of the privileges
    let purse_id = URef::new(mock_purse_id, AccessRights::READ);
    assert!(runtime_context.validate_uref(&purse_id).is_ok());
    let purse_id = URef::new(mock_purse_id, AccessRights::ADD);
    assert!(runtime_context.validate_uref(&purse_id).is_ok());
    let purse_id = URef::new(mock_purse_id, AccessRights::WRITE);
    assert!(runtime_context.validate_uref(&purse_id).is_ok());

    // Purse ID that doesn't match account's purse_id should fail as it's also not
    // in known urefs.
    let purse_id = URef::new([53; 32], AccessRights::READ_ADD_WRITE);
    assert!(runtime_context.validate_uref(&purse_id).is_err());
}

#[test]
fn attenuate_uref_for_system_account() {
    let (_key, account) = mock_account(SYSTEM_ACCOUNT_ADDR);
    let system_contract_uref = URef::new([42; 32], AccessRights::READ_ADD);
    let attenuated_uref = attenuate_uref_for_account(&account, system_contract_uref);

    let access_rights = attenuated_uref
        .access_rights()
        .expect("should have access rights");
    assert_eq!(access_rights, AccessRights::READ_ADD_WRITE);
}

#[test]
fn attenuate_uref_for_user_account() {
    let (_key, account) = mock_account([42; 32]);
    let system_contract_uref = URef::new([42; 32], AccessRights::READ_ADD_WRITE);
    let attenuated_uref = attenuate_uref_for_account(&account, system_contract_uref);

    let access_rights = attenuated_uref
        .access_rights()
        .expect("should have access rights");
    assert_eq!(access_rights, AccessRights::READ);
}
