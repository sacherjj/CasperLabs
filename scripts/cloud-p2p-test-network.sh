#!/usr/bin/env bash
## Update P2P test network if correct repo and branch.
#set -eo pipefail
set -x pipefail

SSH_USERNAME="root"

# Tag and push casperlabsnode docker container when it meets criteria.
if [[ "${TRAVIS_BRANCH}" == "master"  || \
      "${TRAVIS_BRANCH}" == "dev"  || \
      "${TRAVIS_BRANCH}" == "ops-test" \
   ]] && \
   [[ "${TRAVIS_PULL_REQUEST}" = "false" && "${TRAVIS_REPO_SLUG}" = "CasperLabs/CasperLabs" ]] ; then

  echo "Travis branch ${TRAVIS_BRANCH} matched and from repo CasperLabs/CasperLabs. Updating cloud testnet."

  # Add key to ssh-agent
  eval "$(ssh-agent -s)" #start the ssh agent
  openssl aes-256-cbc -K $encrypted_e29707d24bca_key -iv $encrypted_e29707d24bca_iv -in .travis/id_rsa.enc -out /tmp/id_rsa -d
  ssh-add <(cat /tmp/id_rsa)

  # Generate casperlabsnode debian and rpm packages - push to repo and then to p2p test net
  sbt -Dsbt.log.noformat=true clean rholang/bnfc:generate node/rpm:packageBin node/debian:packageBin 

  # Copy to repo so it can be externally downloaded
  artifacts_dir=$(mktemp -d /tmp/artifacts.XXXXXXXX)
  cp node/target/*.deb ${artifacts_dir}/casperlabsnode.deb
  cp node/target/rpm/RPMS/noarch/*.rpm ${artifacts_dir}/casperlabsnode.rpm
  cp node/target/*.deb ${artifacts_dir}/casperlabsnode_${TRAVIS_BRANCH}_all.deb
  cp node/target/rpm/RPMS/noarch/*.rpm ${artifacts_dir}/casperlabsnode-${TRAVIS_BRANCH}.noarch.rpm
  scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -CP 10003 ${artifacts_dir}/* ${SSH_USERNAME}@repo.casperlabs.space:/usr/share/nginx/html/

  # Update casperlabsnode test network containers with branch
  for i in {1..4}; do

    # If first node set casperlabsnode cmd so it acts as bootstrap server
    if [[ $i == 1 ]]; then
      casperlabsnode_cmd="casperlabsnode --port 30304 --standalone --name 0f365f1016a54747b384b386b8e85352 > /var/log/casperlabsnode.log 2>&1 &"
    else
      casperlabsnode_cmd="casperlabsnode --bootstrap casperlabsnode://0f365f1016a54747b384b386b8e85352@10.1.1.2:30304 > /var/log/casperlabsnode.log 2>&1 &"
    fi

    echo "Updating host package and running casperlabsnode"
    ssh_tcp_port=$((40000+$i))
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -p ${ssh_tcp_port} ${SSH_USERNAME}@repo.casperlabs.space "
        rm casperlabsnode_${TRAVIS_BRANCH}_all.deb;
        wget --quiet https://repo.casperlabs.space/casperlabsnode_${TRAVIS_BRANCH}_all.deb;
        pkill -9 java;
        apt -y remove --purge casperlabsnode;
        apt -y install ./casperlabsnode_${TRAVIS_BRANCH}_all.deb;
        ${casperlabsnode_cmd}
        " 
  done

  echo "Running tests on node network"
  # Check that metrics api is functioning with correct peers_total
  sleep 60 # Be sure casperlabsnode network has converged before checking metrics
  all_pass=true
  for i in {1..4}; do
    ssh_tcp_port=$((40000+$i))
    res=$(ssh -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -p ${ssh_tcp_port} ${SSH_USERNAME}@repo.casperlabs.space "
      curl -s 127.0.0.1:40403 | grep "^peers ";
      ")
    if [[ "$res" ==  "peers 3.0" ]]; then
      echo "PASS: Metric \"${res}\" is correct for node $i."
    else
      all_pass=false
      echo "FAIL: Metric \"${res}\" is incorrect for node $i. Metrics or api issue."
    fi
  done
  if [[ $all_pass != true ]]; then
    echo "ERROR: Not all network checks passed."
  fi

else
  echo "Ignored. P2P test net update skipped as it is not correct branch and from CasperLabs/CasperLabs repo."
fi
