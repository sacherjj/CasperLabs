const HEX_TABLE: String[] = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'];

export function hex2bin(hex: String): Uint8Array {
  let bin = new Uint8Array(hex.length / 2);

  for (let i = 0; i < hex.length / 2; i++) {
    // NOTE: hex.substr + parseInt gives weird results under AssemblyScript
    const lo = HEX_TABLE.indexOf(hex[i * 2]);
    assert(lo > -1);
    const hi = HEX_TABLE.indexOf(hex[(i * 2) + 1]);
    assert(hi > -1);
    const number = (lo << 4) | hi;
    bin[i] = number;
  }
  return bin;
}
