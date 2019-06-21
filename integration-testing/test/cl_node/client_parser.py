from collections import defaultdict
import re

"""
Parser of the gRPC message dump for human reading as produced by scalapb and printed by the Scala CasperLabs client.
"""

class MaybeList(list):
    def __getattr__(self, name):
        if len(self) != 1:
            raise Exception(f"Attempt to access attribute '{name}' of a list of length {len(self)}")
        return getattr(self[0], name)


class PropertyBag:
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
        return int(s), line_number
    except ValueError:
        try:
            return float(s), line_number
        except ValueError:
            if s == 'true':
                return True, line_number
            elif s == 'false':
                return False, line_number
            elif s[0] == '"' and s[-1] == '"':
                return s[1:-1], line_number
            else:
                # This must be an enum, for example: READ_ADD_WRITE
                return s, line_number


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


def _parse(tokens):
    d = defaultdict(MaybeList)

    for token, line_number in tokens:
        if token == '}':
            return PropertyBag(dict(d))

        next_token, line_number = next(tokens)
        if next_token == '{':
            d[token].append(_parse(tokens))
        else:
            d[token] = next_token

    raise Exception("Missing closing bracket '}'")

 
def parse(s):
    return _parse(lexer(s))


def parse_show_blocks(s):
    blocks = re.split('^-+$', s, flags=re.MULTILINE)[:-1]
    return [parse(block) for block in blocks]


def parse_show_deploys(s):
    return parse_show_blocks(s)


def parse_show_block(s):
    return parse(s)

