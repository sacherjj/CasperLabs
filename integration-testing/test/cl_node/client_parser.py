
def parse_value(s):
    try:
        return int(s)
    except ValueError:
        return s[1:-1] # unquote string


def parse_line(line):
    k, v = line.split(': ')
    return k.strip(), parse_value(v)


def parse_show_block(s):

    class Block:
        def __init__(self, d):
            self.d = d

        def __getattr__(self, name):
            if name in self.d:
                return self.d[name]
            raise KeyError("Can't find '%s' in block; got %r" % (name, self.d.keys()))

    return Block(dict(parse_line(line) for line in filter(lambda line: ':' in line, s.splitlines())))


def parse_show_blocks(s):
    """
    TODO: remove this and related functions once Python client is integrated into test framework.
    """
    blocks = s.split('-----------------------------------------------------')[:-1]
    return [parse_show_block(b) for b in blocks]
