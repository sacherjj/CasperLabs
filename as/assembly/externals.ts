@external("env", "revert")
export declare function revert(err_code: i32): void;

@external("env", "transfer_to_account")
export declare function transfer_to_account(
  target_ptr: i32,
  target_size: i32,
  amount_ptr: i32,
  amount_size: i32
): i32;

@external("env", "get_arg")
export declare function get_arg(index: u32, dest_ptr: usize, dest_size: u32): i32;

@external("env", "get_arg_size")
export declare function get_arg_size(index: u32, dest_size: u32): i32;

@external("env", "get_main_purse")
export declare function get_main_purse(dest_ptr: usize): void;

@external("env", "get_system_contract")
export declare function get_system_contract(system_contract_index: u32, dest_ptr: usize, dest_size: u32): i32;

@external("env", "call_contract")
export declare function call_contract(key_ptr: usize, key_size: u32, args_ptr: usize, args_size: u32, extra_urefs_ptr: usize, extra_urefs_size: usize, result_size: usize): i32;

@external("env", "read_host_buffer")
export declare function read_host_buffer(dest_ptr: usize, dest_size: u32, bytes_written: usize): i32;
