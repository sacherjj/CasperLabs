from test.cl_node.client_parser import parse, parse_show_blocks



SHOW_BLOCKS = """
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


def test_client_parser_show_blocks():
    r = parse_show_blocks(SHOW_BLOCKS)[0]
    bonds = r.summary.header.state.bonds
    assert len(bonds) == 10
    assert sum(bond.stake for bond in bonds) == 190



DEPLOY = """
deploy {
  deploy_hash: "154999dfe710f669c20a9194c417ea8b0fba597871cc81fcd4f38b769be59c69"
  header {
    account_public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    nonce: 1
    timestamp: 1560707132604
    gas_price: 0
    body_hash: "ee8c135766ee53fbee524cd98e052eb6aae2a3a3eb728cb8250911826e7c9715"
  }
}
processing_results {
  block_info {
    summary {
      block_hash: "afe8789439754b7d54f74ddfc7c8959db3dbfc4f722f3290295a355780791937"
      header {
        parent_hashes: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        justifications {
          validator_public_key: "7dc905c98ef076b046ec160ad0af8edf1774c90ee3a6ab4a58a1c5273cd81693"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "28d7fad7cbd51ef0dac602a763aa9bd87e407b70b8f64e902519ff314d6b63b5"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "f70d7caa0f1421eda6967d7752c6f6dbab92b5cfa52e0ed2a011df485eea975d"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "de82e67f1bedb6fa3f3426941d3dea473ac94c5cfde3741eda08aef7a338188a"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "ff0ffc1a76f64c84f56d653c83c3683d21665edab19562531248ef7a392ab267"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "3d66eed6c64134a1aa8a1e3b44efcb2b437bfa4d9f28229cc55d81e1980b6b8c"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "62dc5feaa7db6f0962e01eb6433d96f65ad226689aafefcd08bf9b1df80c31f9"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "0594de02300b632efefa38588cc73943d043e142ff5307c59df412d6a4ca3722"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "584c7aa8816245a26003a986cbdda8ac5810d27cd566b254257dd582d6b8d7d5"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        justifications {
          validator_public_key: "d18d831337f319ebabe13d57faa796debe235466053bb2040d25fa346b73822e"
          latest_block_hash: "295868cb6edc0636760a855341e66473708127194d0588c7654569d41818be13"
        }
        state {
          pre_state_hash: "caa9c3b449f101cf9e9b6982de957153f4210b8439668134bcfd2bf343b02997"
          post_state_hash: "6a611fc2a45da2acd70ac2007cae31c3da0d102cf4c150c208f78c85dfbcf812"
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
        body_hash: "155fd1dfd5c231045ad37b8d57b8969da5562da1cd2ddec09190a3feff46a141"
        timestamp: 1560707137433
        protocol_version: 1
        deploy_count: 1
        chain_id: "casperlabs"
        validator_block_seq_num: 1
        validator_public_key: "0594de02300b632efefa38588cc73943d043e142ff5307c59df412d6a4ca3722"
        rank: 1
      }
      signature {
        sig_algorithm: "ed25519"
        sig: "3088dad8b9cd1c53f0cbc403f2d49fb54a533f705f898aef1d524fe44851bf63cd2601e8fa33e5b1354b35912854c49dee2bbd1b0878542dbf4e8208a320760c"
      }
    }
    status {
      fault_tolerance: -1.0
      stats {
        block_size_bytes: 961756
        deploy_error_count: 0
      }
    }
  }
  cost: 14902
  is_error: false
  error_message: ""
}



"""

def test_client_parser_deploy():
    r = parse(DEPLOY)
    assert len (r.processing_results.block_info.summary.header.justifications) == 10


def test_client_parser_list_of_values_of_primitive_types():
    r = parse("""
    values: 0
    values: 1
    """)

    assert r.values == [0, 1]
