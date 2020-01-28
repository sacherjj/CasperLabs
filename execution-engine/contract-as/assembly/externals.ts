@external("env", "read_value")
export declare function read_value(key_ptr: usize, key_size: usize, value_size: usize): i32;
@external("env", "write")
export declare function write(key_ptr: usize, key_size: usize, value_ptr: usize, value_size: usize): void;
@external("env", "add")
export declare function add(key_ptr: usize, key_size: usize, value_ptr: usize, value_size: usize): void;
@external("env", "new_uref")
export declare function new_uref(key_ptr: usize, value_ptr: usize, value_size: usize): void;
@external("env", "store_function")
export declare function store_function(function_name_ptr: usize, function_name_size: usize, named_keys_ptr: usize, named_keys_size: usize, uref_addr_ptr: usize): void;
@external("env", "store_function_at_hash")
export declare function store_function_at_hash(function_name_ptr: usize, function_name_size: usize, named_keys_ptr: usize, named_keys_size: usize, hash_ptr: usize): void;
@external("env", "load_named_keys")
export declare function load_named_keys(total_keys: usize, result_size: usize): i32;
@external("env", "get_arg")
export declare function get_arg(index: u32, dest_ptr: usize, dest_size: u32): i32;
@external("env", "get_arg_size")
export declare function get_arg_size(index: u32, dest_size: u32): i32;
@external("env", "ret")
export declare function ret(value_ptr: usize, value_size: usize): void;
@external("env", "call_contract")
export declare function call_contract(key_ptr: usize, key_size: u32, args_ptr: usize, args_size: u32, result_size: usize): i32;
@external("env", "get_key")
export declare function get_key(
    name_ptr: usize,
    name_size: usize,
    output_ptr: usize,
    output_size: usize,
    bytes_written_ptr: usize,
): i32;
@external("env", "has_key")
export declare function has_key(name_ptr: usize, name_size: usize): i32;
@external("env", "put_key")
export declare function put_key(name_ptr: usize, name_size: usize, key_ptr: usize, key_size: usize): void;
@external("env", "remove_key")
export declare function remove_key(name_ptr: usize, name_size: u32): void;
@external("env", "revert")
export declare function revert(err_code: i32): void;
@external("env", "is_valid_uref")
export declare function is_valid_uref(target_ptr: usize, target_size: u32): i32;
@external("env", "add_associated_key")
export declare function add_associated_key(public_key_ptr: usize, weight: i32): i32;
@external("env", "remove_associated_key")
export declare function remove_associated_key(public_key_ptr: usize): i32;
@external("env", "update_associated_key")
export declare function update_associated_key(public_key_ptr: usize, weight: i32): i32;
@external("env", "set_action_threshold")
export declare function set_action_threshold(permission_level: u32, threshold: i32): i32;
@external("env", "get_blocktime")
export declare function get_blocktime(dest_ptr: usize): void;
@external("env", "get_caller")
export declare function get_caller(dest_ptr: usize): void;
@external("env", "create_purse")
export declare function create_purse(purse_id_ptr: usize, purse_id_size: u32): i32;
@external("env", "transfer_to_account")
export declare function transfer_to_account(
    target_ptr: usize,
    target_size: u32,
    amount_ptr: usize,
    amount_size: u32,
): i32;
@external("env", "transfer_from_purse_to_account")
export declare function transfer_from_purse_to_account(
    source_ptr: usize,
    source_size: u32,
    target_ptr: usize,
    target_size: u32,
    amount_ptr: usize,
    amount_size: u32,
):  i32;
@external("env", "transfer_from_purse_to_purse")
export declare function transfer_from_purse_to_purse(
    source_ptr: usize,
    source_size: u32,
    target_ptr: usize,
    target_size: u32,
    amount_ptr: usize,
    amount_size: u32,
): i32;
@external("env", "get_balance")
export declare function get_balance(purse_id_ptr: usize, purse_id_size: usize, result_size: u32): i32;
@external("env", "get_phase")
export declare function get_phase(dest_ptr: usize): void;
// TODO: upgrade_contract_at_uref
@external("env", "upgrade_contract_at_uref")
export declare function upgrade_contract_at_uref(
    name_ptr: usize,
    name_size: u32,
    key_ptr: usize,
    key_size: u32
): i32;
@external("env", "get_system_contract")
export declare function get_system_contract(system_contract_index: u32, dest_ptr: usize, dest_size: u32): i32;
@external("env", "get_main_purse")
export declare function get_main_purse(dest_ptr: usize): void;
@external("env", "read_host_buffer")
export declare function read_host_buffer(dest_ptr: usize, dest_size: u32, bytes_written: usize): i32;
