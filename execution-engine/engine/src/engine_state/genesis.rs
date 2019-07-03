use core::fmt::Write;
use std::collections::btree_map::BTreeMap;
use std::collections::HashMap;
use std::fmt;

use rand::RngCore;
use rand_chacha::ChaChaRng;

use common::bytesrepr::ToBytes;
use common::key::Key;
use common::uref::{AccessRights, URef};
use common::value::account::{PublicKey, PurseId};
use common::value::{Contract, Value, U512};
use engine_state::execution_effect::ExecutionEffect;
use engine_state::op::Op;
use engine_state::utils::WasmiBytes;
use execution;
use shared::init;
use shared::newtypes::Blake2bHash;
use shared::transform::{Transform, TypeMismatch};
use storage::global_state::CommitResult;

pub const POS_PURSE: &str = "pos_purse";
pub const POS_PUBLIC_ADDRESS: &str = "pos_public_address";
pub const POS_PRIVATE_ADDRESS: &str = "pos_private_address";
pub const MINT_PUBLIC_ADDRESS: &str = "mint_public_address";
pub const MINT_PRIVATE_ADDRESS: &str = "mint_private_address";
pub const GENESIS_ACCOUNT_PURSE: &str = "genesis_account_purse";
pub const MINT_GENESIS_ACCOUNT_BALANCE_UREF: &str = "mint_genesis_account_balance_uref";
pub const MINT_POS_BALANCE_UREF: &str = "mint_pos_balance_uref";

/// Structure for tracking URefs generated in the genesis process.
pub struct GenesisURefsSource(BTreeMap<&'static str, URef>);

impl GenesisURefsSource {
    fn create_genesis_rng() -> ChaChaRng {
        // We are using easy to recover address and nonce as seeds so that the addresses
        // can be recomputed by the EngineState for PoS purposes.
        // This should never clash with the deploy's PRNG as there is no Ed25519 private key that
        // corresponds to `000..00` public key. Even if there was, because we are using nonce=0
        // and any valid deploy starts with nonce=1 the seed to deploy's PRNG will be different.
        execution::create_rng([0u8; 32], 0)
    }

    pub fn get_uref(&self, label: &str) -> URef {
        *self
            .0
            .get(label)
            .unwrap_or_else(|| panic!("URef {} wasn't generated.", label))
    }

    pub fn get_pos_address(&self) -> URef {
        *self.0.get(POS_PRIVATE_ADDRESS).unwrap() // It's safe to unwrap as we have included it when creating `GenesisURefsSource`.
    }
}

impl Default for GenesisURefsSource {
    fn default() -> Self {
        // Pregenerates all URefs so that they are statically known.
        let mut chacha_rng = GenesisURefsSource::create_genesis_rng();
        let mut urefs_map = BTreeMap::new();
        urefs_map.insert(POS_PURSE, create_uref(&mut chacha_rng));
        urefs_map.insert(POS_PUBLIC_ADDRESS, create_uref(&mut chacha_rng));
        urefs_map.insert(POS_PRIVATE_ADDRESS, create_uref(&mut chacha_rng));
        urefs_map.insert(MINT_PUBLIC_ADDRESS, create_uref(&mut chacha_rng));
        urefs_map.insert(MINT_PRIVATE_ADDRESS, create_uref(&mut chacha_rng));
        urefs_map.insert(GENESIS_ACCOUNT_PURSE, create_uref(&mut chacha_rng));
        urefs_map.insert(
            MINT_GENESIS_ACCOUNT_BALANCE_UREF,
            create_uref(&mut chacha_rng),
        );
        urefs_map.insert(MINT_POS_BALANCE_UREF, create_uref(&mut chacha_rng));

        GenesisURefsSource(urefs_map)
    }
}

fn create_uref<R: RngCore>(rng: &mut R) -> URef {
    let mut buff = [0u8; 32];
    rng.fill_bytes(&mut buff);
    URef::new(buff, AccessRights::READ_ADD_WRITE)
}

fn create_local_key<T: ToBytes>(seed: [u8; 32], key: T) -> Result<Key, common::bytesrepr::Error> {
    let local_key_bytes = key.to_bytes()?;
    Ok(Key::local(seed, &local_key_bytes))
}

fn create_mint_effects(
    rng: &GenesisURefsSource,
    genesis_account_addr: [u8; 32],
    initial_tokens: U512,
    mint_code_bytes: WasmiBytes,
    pos_bonded_balance: U512,
    protocol_version: u64,
) -> Result<HashMap<Key, Value>, execution::Error> {
    let mut tmp: HashMap<Key, Value> = HashMap::new();

    // Create (public_uref, mint_contract_uref)
    let pos_purse = rng.get_uref(POS_PURSE);

    let public_uref = rng.get_uref(MINT_PUBLIC_ADDRESS);

    let mint_contract_uref = rng.get_uref(MINT_PRIVATE_ADDRESS);

    // Store (public_uref, mint_contract_uref) in global state

    tmp.insert(
        Key::URef(public_uref),
        Value::Key(Key::URef(mint_contract_uref)),
    );

    let purse_id_uref = rng.get_uref(GENESIS_ACCOUNT_PURSE);

    // Create genesis genesis_account
    let genesis_account = {
        // All blessed / system contract public urefs MUST be added to the genesis account's known_urefs
        // TODO: do we need to deal with NamedKey ???
        let known_urefs = &[
            (String::from("mint"), Key::URef(public_uref)),
            (
                mint_contract_uref.as_string(),
                Key::URef(mint_contract_uref),
            ),
        ];
        let purse_id = PurseId::new(purse_id_uref);
        init::create_genesis_account(genesis_account_addr, purse_id, known_urefs)
    };

    // Store (genesis_account_addr, genesis_account) in global state

    tmp.insert(
        Key::Account(genesis_account_addr),
        Value::Account(genesis_account),
    );

    // Initializing and persisting mint

    // Create (purse_id_local_key, balance_uref) (for mint-local state)

    let purse_id_local_key = create_local_key(mint_contract_uref.addr(), purse_id_uref.addr())?;

    let balance_uref = rng.get_uref(MINT_GENESIS_ACCOUNT_BALANCE_UREF);

    let balance_uref_key = Key::URef(balance_uref);

    // Store (purse_id_local_key, balance_uref_key) in local state

    tmp.insert(purse_id_local_key, Value::Key(balance_uref_key));

    // Store (pos_purse_local_key, pos_balance_uref_key) in local state.
    let pos_balance_uref = rng.get_uref(MINT_POS_BALANCE_UREF);
    let pos_balance_uref_key = Key::URef(pos_balance_uref);

    let pos_purse_local_key = create_local_key(mint_contract_uref.addr(), pos_purse.addr())?;

    tmp.insert(pos_purse_local_key, Value::Key(pos_balance_uref_key));

    let pos_balance: Value = Value::UInt512(pos_bonded_balance);

    // Store (pos_balance_uref_key, pos_balance) in GlobalState
    tmp.insert(pos_balance_uref_key, pos_balance);

    // Create balance

    let balance: Value = Value::UInt512(initial_tokens);

    // Store (balance_uref_key, balance) in GlobalState

    tmp.insert(balance_uref_key, balance);

    // Create mint_contract
    let mint_known_urefs = {
        let mut ret: BTreeMap<String, Key> = BTreeMap::new();
        ret.insert(balance_uref.as_string(), balance_uref_key);
        // Insert PoS balance URef and its initial stakes so that PoS.
        ret.insert(pos_balance_uref.as_string(), pos_balance_uref_key);
        ret.insert(
            mint_contract_uref.as_string(),
            Key::URef(mint_contract_uref),
        );
        ret
    };

    let mint_contract: Contract =
        Contract::new(mint_code_bytes.into(), mint_known_urefs, protocol_version);

    // Store (mint_contract_uref, mint_contract) in global state

    tmp.insert(
        Key::URef(mint_contract_uref),
        Value::Contract(mint_contract),
    );

    Ok(tmp)
}

fn create_pos_effects(
    rng: &GenesisURefsSource,
    pos_code: WasmiBytes,
    genesis_validators: Vec<(PublicKey, U512)>,
    protocol_version: u64,
) -> Result<HashMap<Key, Value>, execution::Error> {
    let mut tmp: HashMap<Key, Value> = HashMap::new();

    // Create (public_pos_uref, pos_contract_uref)
    let public_pos_address = rng.get_uref(POS_PUBLIC_ADDRESS);
    let pos_uref = rng.get_uref(POS_PRIVATE_ADDRESS);

    // Mateusz: Maybe we could make `public_pos_address` a Key::Hash after all.
    // Store public PoS address -> PoS contract relation
    tmp.insert(
        Key::URef(public_pos_address),
        Value::Key(Key::URef(pos_uref)),
    );

    // Add genesis validators to PoS contract object.
    // For now, we are storing validators in `known_urefs` map of the PoS contract
    // in the form: key: "v_{validator_pk}_{validator_stake}", value: doesn't matter.
    let mut known_urefs: BTreeMap<String, Key> = genesis_validators
        .iter()
        .map(|(pub_key, balance)| {
            let key_bytes = pub_key.value();
            let mut hex_key = String::with_capacity(64);
            for byte in &key_bytes[..32] {
                write!(hex_key, "{:02x}", byte).unwrap();
            }
            let mut uref = String::new();
            uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                .unwrap();
            uref
        })
        .map(|key| (key, Key::Hash([0u8; 32])))
        .collect();

    let pos_purse = rng.get_uref(POS_PURSE);

    known_urefs.insert(POS_PURSE.to_string(), Key::URef(pos_purse));

    // Create PoS Contract object.
    let contract = Contract::new(pos_code.into(), known_urefs, protocol_version);

    // Store PoS code under `pos_uref`.
    tmp.insert(Key::URef(pos_uref), Value::Contract(contract));

    Ok(tmp)
}

// TODO: Post devnet, make genesis creation regular contract execution.
pub fn create_genesis_effects(
    genesis_account_addr: [u8; 32],
    initial_tokens: U512,
    mint_code_bytes: WasmiBytes,
    pos_code_bytes: WasmiBytes,
    genesis_validators: Vec<(PublicKey, U512)>,
    protocol_version: u64,
) -> Result<ExecutionEffect, execution::Error> {
    let rng = GenesisURefsSource::default();

    let genesis_validator_stakes: U512 = genesis_validators
        .iter()
        .map(|t| t.1)
        .fold(U512::zero(), |a, b| a + b);

    let pos_effects =
        create_pos_effects(&rng, pos_code_bytes, genesis_validators, protocol_version)?;

    let mint_effects = create_mint_effects(
        &rng,
        genesis_account_addr,
        initial_tokens,
        mint_code_bytes,
        genesis_validator_stakes,
        protocol_version,
    )?;

    let mut execution_effect: ExecutionEffect = Default::default();

    for (k, v) in mint_effects.into_iter().chain(pos_effects.into_iter()) {
        let k = if let Key::URef(_) = k {
            k.normalize()
        } else {
            k
        };
        execution_effect.ops.insert(k, Op::Write);
        execution_effect.transforms.insert(k, Transform::Write(v));
    }

    Ok(execution_effect)
}

pub enum GenesisResult {
    RootNotFound,
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Success {
        post_state_hash: Blake2bHash,
        effect: ExecutionEffect,
    },
}

impl fmt::Display for GenesisResult {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            GenesisResult::RootNotFound => write!(f, "Root not found"),
            GenesisResult::KeyNotFound(key) => write!(f, "Key not found: {}", key),
            GenesisResult::TypeMismatch(type_mismatch) => {
                write!(f, "Type mismatch: {:?}", type_mismatch)
            }
            GenesisResult::Success {
                post_state_hash,
                effect,
            } => write!(f, "Success: {} {:?}", post_state_hash, effect),
        }
    }
}

impl GenesisResult {
    pub fn from_commit_result(commit_result: CommitResult, effect: ExecutionEffect) -> Self {
        match commit_result {
            CommitResult::RootNotFound => GenesisResult::RootNotFound,
            CommitResult::KeyNotFound(key) => GenesisResult::KeyNotFound(key),
            CommitResult::TypeMismatch(type_mismatch) => GenesisResult::TypeMismatch(type_mismatch),
            CommitResult::Success(post_state_hash) => GenesisResult::Success {
                post_state_hash,
                effect,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::btree_map::BTreeMap;
    use std::collections::HashMap;

    use common::key::Key;
    use common::value::account::PublicKey;
    use common::value::{Contract, Value, U512};
    use engine_state::create_genesis_effects;
    use engine_state::genesis::{
        GenesisURefsSource, GENESIS_ACCOUNT_PURSE, MINT_GENESIS_ACCOUNT_BALANCE_UREF,
        MINT_POS_BALANCE_UREF, MINT_PRIVATE_ADDRESS, MINT_PUBLIC_ADDRESS, POS_PRIVATE_ADDRESS,
        POS_PUBLIC_ADDRESS,
    };
    use engine_state::utils::{pos_validator_key, WasmiBytes};
    use shared::test_utils;
    use shared::transform::Transform;
    use wasm_prep::wasm_costs::WasmCosts;

    use super::{create_local_key, POS_PURSE};

    const GENESIS_ACCOUNT_ADDR: [u8; 32] = [6u8; 32];
    const PROTOCOL_VERSION: u64 = 1;
    const EXPECTED_GENESIS_TRANSFORM_COUNT: usize = 9; // 7 writes for Mint and 2 for PoS.
    const INITIAL_GENESIS_ACCOUNT_BALANCE: &str = "1000";
    const INITIAL_POS_VALIDATORS_BALANCE: &str = "15000";

    fn get_initial_tokens(initial_balance: &str) -> U512 {
        U512::from_dec_str(initial_balance).expect("should create U512")
    }

    fn get_mint_code_bytes() -> WasmiBytes {
        let raw_bytes = test_utils::create_empty_wasm_module_bytes();
        WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free()).expect("should create wasmi bytes")
    }

    fn get_pos_code_bytes() -> WasmiBytes {
        let raw_bytes = test_utils::create_empty_wasm_module_bytes();
        WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free()).expect("should create wasmi bytes")
    }

    fn get_genesis_transforms() -> HashMap<Key, Transform> {
        let initial_genesis_account_balance = get_initial_tokens(INITIAL_GENESIS_ACCOUNT_BALANCE);
        let initial_pos_validators_balance = get_initial_tokens(INITIAL_POS_VALIDATORS_BALANCE);

        let mint_code_bytes = get_mint_code_bytes();
        let pos_code_bytes = get_pos_code_bytes();
        let genesis_validators = vec![(PublicKey::new([1u8; 32]), initial_pos_validators_balance)];

        create_genesis_effects(
            GENESIS_ACCOUNT_ADDR,
            initial_genesis_account_balance,
            mint_code_bytes,
            pos_code_bytes,
            genesis_validators,
            PROTOCOL_VERSION,
        )
        .expect("should create effects")
        .transforms
    }

    fn is_write(transform: &Transform) -> bool {
        if let Transform::Write(_) = transform {
            true
        } else {
            false
        }
    }

    fn extract_transform_key(effects: &HashMap<Key, Transform>, key: &Key) -> Option<Key> {
        if let Transform::Write(Value::Key(value)) =
            effects.get(&key.normalize()).expect("should have value")
        {
            Some(*value)
        } else {
            None
        }
    }

    fn extract_transform_u512(effects: &HashMap<Key, Transform>, key: &Key) -> Option<U512> {
        if let Transform::Write(Value::UInt512(value)) =
            effects.get(key).expect("should have value")
        {
            Some(*value)
        } else {
            None
        }
    }

    fn extract_transform_contract_bytes(
        effects: &HashMap<Key, Transform>,
        key: &Key,
    ) -> Option<Contract> {
        if let Transform::Write(Value::Contract(value)) =
            effects.get(&key.normalize()).expect("should have value")
        {
            Some(value.to_owned())
        } else {
            None
        }
    }

    #[test]
    fn create_genesis_effects_creates_expected_effects() {
        let transforms = get_genesis_transforms();

        assert_eq!(transforms.len(), EXPECTED_GENESIS_TRANSFORM_COUNT);

        assert!(transforms.iter().all(|(_, effect)| is_write(effect)));
    }

    #[test]
    fn create_genesis_effects_stores_contracts_uref_at_public_uref() {
        let rng = GenesisURefsSource::default();

        let mint_public_address = rng.get_uref(MINT_PUBLIC_ADDRESS);
        let pos_public_address = rng.get_uref(POS_PUBLIC_ADDRESS);

        let mint_public_uref_key = Key::URef(mint_public_address);
        let pos_public_uref_key = Key::URef(pos_public_address);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&mint_public_uref_key.normalize()),
            "Should have expected Mint public_uref"
        );

        assert!(
            transforms.contains_key(&pos_public_uref_key.normalize()),
            "Should have expected PoS key."
        );

        let actual_mint_private_address = extract_transform_key(&transforms, &mint_public_uref_key)
            .expect("transform was not a write of a key");
        let actual_pos_private_address = extract_transform_key(&transforms, &pos_public_uref_key)
            .expect("Transforms did not contain PoS public uref Write.");

        let mint_private_address = rng.get_uref(MINT_PRIVATE_ADDRESS);
        let pos_private_address = rng.get_uref(POS_PRIVATE_ADDRESS);

        // the value under the outer mint_contract_uref should be a key value pointing at
        // the current contract bytes
        assert_eq!(
            actual_mint_private_address,
            Key::URef(mint_private_address),
            "invalid mint contract indirection"
        );

        assert_eq!(
            actual_pos_private_address,
            Key::URef(pos_private_address),
            "Invalid PoS contract indirection"
        );
    }

    #[test]
    fn create_genesis_effects_stores_mint_contract_code_at_mint_contract_uref() {
        let rng = GenesisURefsSource::default();

        let mint_contract_uref = rng.get_uref(MINT_PRIVATE_ADDRESS);

        // this is passing as currently designed, but see bug: EE-380
        let mint_contract_uref_key = Key::URef(mint_contract_uref);

        let transforms = get_genesis_transforms();

        let actual = extract_transform_contract_bytes(&transforms, &mint_contract_uref_key)
            .expect("transform was not a write of a key");

        let mint_code_bytes = get_mint_code_bytes();

        // this is passing as currently designed, but see bug: EE-380
        let balance_uref = rng.get_uref(MINT_GENESIS_ACCOUNT_BALANCE_UREF);

        let balance_uref_key = Key::URef(balance_uref);

        let pos_balance_uref = rng.get_uref(MINT_POS_BALANCE_UREF);
        let pos_balance_uref_key = Key::URef(pos_balance_uref);

        let mint_known_urefs = {
            let mut ret: BTreeMap<String, Key> = BTreeMap::new();
            ret.insert(
                mint_contract_uref.as_string(),
                Key::URef(mint_contract_uref),
            );
            ret.insert(pos_balance_uref.as_string(), pos_balance_uref_key);
            ret.insert(balance_uref.as_string(), balance_uref_key);
            ret
        };

        let mint_contract: Contract = Contract::new(mint_code_bytes.into(), mint_known_urefs, 1);

        // the value under the mint_contract_uref_key should be the current contract bytes
        assert_eq!(actual, mint_contract, "invalid mint contract bytes");
    }

    #[test]
    fn create_genesis_effects_stores_local_keys_balance_urefs_associations() {
        let rng = GenesisURefsSource::default();

        let pos_purse = rng.get_uref(POS_PURSE);
        let expected_mint_pos_balance_uref = rng.get_uref(MINT_POS_BALANCE_UREF);

        let mint_contract_uref = rng.get_uref(MINT_PRIVATE_ADDRESS);

        let purse_id_uref = rng.get_uref(GENESIS_ACCOUNT_PURSE);

        let purse_id_local_key = create_local_key(mint_contract_uref.addr(), purse_id_uref.addr())
            .expect("Should create local key.");

        let pos_purse_local_key = create_local_key(mint_contract_uref.addr(), pos_purse.addr())
            .expect("Should create local key.");

        let balance_uref = rng.get_uref(MINT_GENESIS_ACCOUNT_BALANCE_UREF);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&purse_id_local_key),
            "transforms should contain purse_id_local_key"
        );

        assert!(
            transforms.contains_key(&pos_purse_local_key),
            "transforms should contain pos_purse_local_key"
        );

        let mint_genesis_account_balance_uref =
            extract_transform_key(&transforms, &purse_id_local_key)
                .expect("transform was not a write of a key");

        assert_eq!(
            mint_genesis_account_balance_uref,
            Key::URef(balance_uref),
            "invalid balance indirection"
        );

        let mint_pos_balance_uref = extract_transform_key(&transforms, &pos_purse_local_key)
            .expect("Transform was not a write of a key.");

        assert_eq!(
            mint_pos_balance_uref,
            Key::URef(expected_mint_pos_balance_uref),
            "create_genesis_effects should store PoS purse -> balance association."
        );
    }

    #[test]
    fn create_genesis_effects_balances_at_balance_urefs() {
        let rng = GenesisURefsSource::default();

        let mint_contract_uref = rng.get_uref(MINT_PRIVATE_ADDRESS);

        let genesis_account_purse = rng.get_uref(GENESIS_ACCOUNT_PURSE);
        let pos_purse = rng.get_uref(POS_PURSE);

        let genesis_account_purse_id_local_key =
            create_local_key(mint_contract_uref.addr(), genesis_account_purse.addr())
                .expect("Mint should create local key for genesis account purse.");

        let pos_validators_balance_local_key =
            create_local_key(mint_contract_uref.addr(), pos_purse.addr())
                .expect("Mint should create local key for PoS purse.");

        let genesis_account_balance_uref = rng.get_uref(MINT_GENESIS_ACCOUNT_BALANCE_UREF);
        let pos_balance_uref = rng.get_uref(MINT_POS_BALANCE_UREF);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&genesis_account_purse_id_local_key),
            "transforms should contain account_purse_id_local_key",
        );

        assert!(
            transforms.contains_key(&pos_validators_balance_local_key),
            "Transforms should contain pos_validators_balance_local_key",
        );

        let genesis_account_balance_uref_key = Key::URef(genesis_account_balance_uref).normalize();
        let pos_balance_uref_key = Key::URef(pos_balance_uref).normalize();

        let actual_genesis_account_balance =
            extract_transform_u512(&transforms, &genesis_account_balance_uref_key)
                .expect("transform was not a write of a key");

        let actual_pos_validators_balance =
            extract_transform_u512(&transforms, &pos_balance_uref_key)
                .expect("transform was not a write of a key");

        let initial_genesis_account_balance = get_initial_tokens(INITIAL_GENESIS_ACCOUNT_BALANCE);
        let initial_pos_validators_balance = get_initial_tokens(INITIAL_POS_VALIDATORS_BALANCE);

        // the value under the outer balance_uref_key should be a U512 value (the actual balance)
        assert_eq!(
            actual_genesis_account_balance, initial_genesis_account_balance,
            "Invalid Genesis account balance"
        );
        assert_eq!(
            actual_pos_validators_balance, initial_pos_validators_balance,
            "Invalid PoS genesis validators' balance."
        );
    }

    #[test]
    fn create_genesis_effects_stores_genesis_account_at_genesis_account_addr() {
        let account_key = Key::Account(GENESIS_ACCOUNT_ADDR);

        let transforms = get_genesis_transforms();

        let mint_public_uref = {
            let account_transform = transforms
                .get(&account_key)
                .expect("should have expected account");

            if let Transform::Write(Value::Account(account)) = account_transform {
                account.urefs_lookup().get("mint")
            } else {
                None
            }
            .expect("should have uref")
        };

        let mint_private_uref = {
            let mint_public_uref_transform = transforms
                .get(&mint_public_uref.normalize())
                .expect("should have mint public uref tranform");

            if let Transform::Write(Value::Key(key @ Key::URef(_))) = mint_public_uref_transform {
                Some(key)
            } else {
                None
            }
            .expect("should have uref")
        };

        let actual_mint_contract_bytes = {
            let mint_contract_transform = transforms
                .get(&mint_private_uref.normalize())
                .expect("should have expected uref");

            if let Transform::Write(Value::Contract(contract)) = mint_contract_transform {
                Some(contract.bytes())
            } else {
                None
            }
            .expect("should have contract")
        };

        let expected_mint_contract_bytes: Vec<u8> = get_mint_code_bytes().into();

        assert_eq!(
            actual_mint_contract_bytes,
            expected_mint_contract_bytes.as_slice()
        );
    }

    #[test]
    fn create_pos_effects() {
        let rng = GenesisURefsSource::default();

        let genesis_validator_a_public_key = PublicKey::new([0u8; 32]);
        let genesis_validator_a_stake = U512::from(1000);

        let genesis_validator_b_public_key = PublicKey::new([1u8; 32]);
        let genesis_validator_b_stake = U512::from(200);

        let genesis_validators = vec![
            (genesis_validator_a_public_key, genesis_validator_a_stake),
            (genesis_validator_b_public_key, genesis_validator_b_stake),
        ];

        let pos_purse = rng.get_uref(POS_PURSE);

        let public_pos_address = rng.get_uref(POS_PUBLIC_ADDRESS);

        let pos_contract_uref = rng.get_uref(POS_PRIVATE_ADDRESS);

        let pos_contract_bytes = get_pos_code_bytes();

        let pos_effects = {
            super::create_pos_effects(&rng, pos_contract_bytes.clone(), genesis_validators, 1)
                .expect("Creating PoS effects in test should not fail.")
        };

        assert_eq!(
            pos_effects.get(&Key::URef(public_pos_address)),
            Some(&Value::Key(Key::URef(pos_contract_uref))),
            "Public URef should point at PoS contract URef."
        );

        let pos_contract = match pos_effects
            .get(&Key::URef(pos_contract_uref))
            .expect("PoS contract should be stored in GlobalState.")
        {
            Value::Contract(contract) => contract,
            _ => panic!("Expected Value::Contract"),
        };

        // rustc isn't smart enough to figure that out
        let pos_contract_raw: Vec<u8> = pos_contract_bytes.into();
        assert_eq!(pos_contract.bytes().to_vec(), pos_contract_raw);
        assert_eq!(pos_contract.urefs_lookup().len(), 3); // 2 for bonded validators, 1 for PoS purse.

        let validator_a_name: String =
            pos_validator_key(genesis_validator_a_public_key, genesis_validator_a_stake);
        let validator_b_name: String =
            pos_validator_key(genesis_validator_b_public_key, genesis_validator_b_stake);

        assert!(
            pos_contract.urefs_lookup().contains_key(&validator_a_name),
            "create_pos_effects should correctly store genesis validators in PoS known_urefs_map."
        );
        assert!(
            pos_contract.urefs_lookup().contains_key(&validator_b_name),
            "create_pos_effects should correctly store genesis validators in PoS known_urefs_map."
        );

        assert_eq!(
            pos_contract.urefs_lookup().get(POS_PURSE),
            Some(&Key::URef(pos_purse)),
            "create_pos_effects should store POS_PURSE in PoS contract's known urefs map."
        );
    }

}
