from collections import defaultdict

SAMPLE = """
------------- block @ 0 ---------------
summary {
  block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
  header {
    state {
      pre_state_hash: "caa9c3b449f101cf9e9b6982de957153f4210b8439668134bcfd2bf343b02997"
      post_state_hash: "caa9c3b449f101cf9e9b6982de957153f4210b8439668134bcfd2bf343b02997"
      bonds {
        validator_public_key: "0594de02300b632efefa38588cc73943d043e142ff5307c59df412d6a4ca3722"
        stake: 10
      }
      bonds {
        validator_public_key: "28d7fad7cbd51ef0dac602a763aa9bd87e407b70b8f64e902519ff314d6b63b5"
        stake: 18
      }
      bonds {
        validator_public_key: "3d66eed6c64134a1aa8a1e3b44efcb2b437bfa4d9f28229cc55d81e1980b6b8c"
        stake: 14
      }
      bonds {
        validator_public_key: "584c7aa8816245a26003a986cbdda8ac5810d27cd566b254257dd582d6b8d7d5"
        stake: 16
      }
      bonds {
        validator_public_key: "62dc5feaa7db6f0962e01eb6433d96f65ad226689aafefcd08bf9b1df80c31f9"
        stake: 26
      }
      bonds {
        validator_public_key: "7dc905c98ef076b046ec160ad0af8edf1774c90ee3a6ab4a58a1c5273cd81693"
        stake: 28
      }
      bonds {
        validator_public_key: "d18d831337f319ebabe13d57faa796debe235466053bb2040d25fa346b73822e"
        stake: 12
      }
      bonds {
        validator_public_key: "de82e67f1bedb6fa3f3426941d3dea473ac94c5cfde3741eda08aef7a338188a"
        stake: 22
      }
      bonds {
        validator_public_key: "f70d7caa0f1421eda6967d7752c6f6dbab92b5cfa52e0ed2a011df485eea975d"
        stake: 24
      }
      bonds {
        validator_public_key: "ff0ffc1a76f64c84f56d653c83c3683d21665edab19562531248ef7a392ab267"
        stake: 20
      }
    }
    body_hash: "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"
    timestamp: 1
    protocol_version: 1
    deploy_count: 0
    chain_id: "casperlabs"
    validator_block_seq_num: 0
    validator_public_key: ""
    rank: 0
  }
}
status {
  fault_tolerance: -0.70526314
}

-----------------------------------------------------


count: 3


"""

class Bag:
    def __init__(self, d):
        self.d = d

    def __getattr__(self, name):
        if name in self.d:
            return self.d[name]
        raise KeyError("Can't find '%s' in block; got %r" % (name, self.d.keys()))

    def __str__(self):
        return f'{self.d}'

    def __repr__(self):
        return str(self)


def attribute_name(s, line_number):
    return (s.endswith(':') and s[:-1] or s, line_number)


def value(s, line_number):
    try:
        return (int(s), line_number)
    except ValueError:
        try:
            return (float(s), line_number)
        except ValueError:
            assert s[0] == '"' and s[-1] == '"'
            return (s[1:-1], line_number)
    

def lexer(s):
    for line_number, line in enumerate((l.strip() for l in s.splitlines()), 1):
        if not line or line.startswith('--'):
            continue
        if line == '}':
            yield ('}', line_number)
        else:
            l, r = line.split(maxsplit = 1)
            yield attribute_name(l, line_number)
            if r == '{':
                yield ('{', line_number)
            else:
                yield value(r, line_number)
    yield ('}', line_number + 1)


def parse(tokens):
    d = defaultdict(list)

    for token, line_number in tokens:
        if token == '}':
            break

        v, line_number = next(tokens)
        if v == '{':
            d[token].append(parse(tokens))
        else:
            d[token] = v

    return Bag(dict(d))
 

def parse_show_blocks(s):
    bag = parse(lexer(s))
    return bag.summary
    

def parse_show_block(s):
    return parse(lexer(s))


if __name__ == '__main__':
    print(parse_show_block(SAMPLE))

