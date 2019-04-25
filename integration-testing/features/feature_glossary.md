# Feature Glossary

This will hold common phrases used in tests and what they mean.  Because BDD using Gherkin has fixtures and assertions
created with phrases, it is important that these be common.

## Network Spin up

### "Single Node Network"

 - Bootstrap Node is created
 
### "3 Node Star Network"

 - Networks A, B, C created
 - Bootstrap Node is created
    - with Networks A, B, C
 - Node-1 is created
    - with Network-A
 - Node-2 is created
    - with Network-B
 - Node-3 is created
    - with Network-C

```
               Node-1
                 |
                 A
                 |
 Node-2 --B-- Bootstrap --C-- Node-3
```
   
### "3 Node Network"

 - Network A created
 - Bootstrap Node is created
    - with Network A
 - Node-1 is created
    - with Network A
 - Node-2 is created
    - with Network A
 - Node-3 is created
    - with Network A
```
          ____ Node-1 _______
         /       |           \
        A        A            A
       /         |             \
 Node-2 --A-- Bootstrap --A-- Node-3
       \                      /
        \_________A__________/
```  

### "Mesh Network"

 - Networks A, B, C, D, E, F, G, H, I, J, K, L created
 - Bootstrap Node is created
    - with Networks D, F, G, I
 - Node-2 is created
    - with Networks A, B, D
 - Node-1 is created
    - with Networks A, C
 - Node-4 is created
    - with Networks C, F, H
 - Node-6 is created
    - with Networks H, K
 - Node-7 is created
    - with Networks K, I, L
 - Node-8 is created
    - with Networks J, L
 - Node-5 is created
    - with Networks E, G, J
 - Node-3 is created
    - with Networking B, E

 
```
  Node-1 -A- Node-2 -B- Node-3
    |C         |D        |E
  Node-4 -F- Bootst -G- Node 5
    |H         |I        |J
  Node-6 -K- Node-7 -L- Node-8
```

### Possible Other Networks needed:

 - "Partitioned Network"
   - Can we have a breakable network junction in docker?

## Account Creation

### "Account Funded {X} Nonce {Y}"

    - Account is created
    - Account is funded with {X} tokens
    - Account nonce is set as {Y}

## Deploy

### "Deploy {Account} {Nonce} {Payment} {Session}"

    - Deploy using
        - {Account} for signing
        - {Nonce} for nonce
        - {Payment} contract
        - {Session} contract

## Propose

