export function hex2bin(hex: string): Uint8Array {
  let bin = new Uint8Array(hex.length / 2);
  for (let i = 0; i < hex.length / 2; i++) {
    bin[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bin;
}
