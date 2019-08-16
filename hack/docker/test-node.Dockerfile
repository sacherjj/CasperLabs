FROM casperlabs/node:latest

# Using iproute2 for network simulation with `tc`.
# iptables can also be used to block individual ports.
# Double update due to: Could not open file /var/lib/apt/lists/deb.debian.org_debian_dists_stretch-backports_main_binary-amd64_Packages.diff_Index - open (2: No such file or directory)
RUN apt-get clean && rm -rf /var/lib/apt/lists/* && (apt-get update || apt-get update) && apt-get install -yq iproute2 iptables curl sed nmap

COPY .genesis/system-contracts /opt/docker/system-contracts
ENV CL_CASPER_MINT_CODE_PATH /opt/docker/system-contracts/mint_token.wasm
ENV CL_CASPER_POS_CODE_PATH /opt/docker/system-contracts/pos.wasm
