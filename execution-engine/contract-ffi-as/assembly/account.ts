import * as externals from "./externals";
import {toBytesArrayU8} from "./bytesrepr";
import {arrayToTyped} from "./utils";

export enum AddKeyFailure {
    // Success
    Ok = 0,
    // Unable to add new associated key because maximum amount of keys is reached
    MaxKeysLimit = 1,
    // Unable to add new associated key because given key already exists
    DuplicateKey = 2,
    // Unable to add new associated key due to insufficient permissions
    PermissionDenied = 3,
}

export enum SetThresholdFailure {
    Ok = 0,
    // New threshold should be lower or equal than deployment threshold
    KeyManagementThresholdError = 1,
    // New threshold should be lower or equal than key management threshold
    DeploymentThresholdError = 2,
    // Unable to set action threshold due to insufficient permissions
    PermissionDeniedError = 3,
    // New threshold should be lower or equal than total weight of associated keys
    InsufficientTotalWeight = 4,
}

export enum ActionType {
    // Required by deploy execution.
    Deployment = 0,
    // Required when adding/removing associated keys, changing threshold levels.
    KeyManagement = 1,
}

export function addAssociatedKey(publicKey: Array<u8>, weight: i32): AddKeyFailure {
    const publicKeyBytes = arrayToTyped(publicKey);
    const ret = externals.add_associated_key(publicKeyBytes.dataStart, weight);
    return <AddKeyFailure>ret;
}


export function setActionThreshold(actionType: ActionType, thresholdValue: u8): SetThresholdFailure {
    const ret = externals.set_action_threshold(<i32>actionType, thresholdValue);
    return <SetThresholdFailure>ret;
}
