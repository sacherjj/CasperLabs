// Converts typed array to array
export function typedToArray(arr: Uint8Array): Array<u8> {
    let result = new Array<u8>(arr.length);
    for (let i = 0; i < arr.length; i++) {
        result[i] = arr[i];
    }
    return result;
  }
  
  // Converts typed array to array
  export function arrayToTyped(arr: Array<u8>): Uint8Array {
    let result = new Uint8Array(arr.length);
    for (let i = 0; i < arr.length; i++) {
        result[i] = arr[i];
    }
    return result;
  }
