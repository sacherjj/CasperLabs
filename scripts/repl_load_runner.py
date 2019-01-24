#!/usr/bin/env python3
from pexpect import replwrap
import subprocess
import argparse
import docker

parser = argparse.ArgumentParser()
parser.add_argument("-g", "--grpc-host", dest='grpc_host', default="node0.testnet1.casperlabs", help="set grpc host")
parser.add_argument("-r", "--repeat", dest='repeat', type=int, default=50, help="set repetition count")
parser.add_argument("-p", "--prompt", dest='prompt', type=str, default="rholang $ ", help="set REPL prompt")
parser.add_argument("-c", "--cpus", dest='cpus', type=int, default=1, help="set docker cpus for repl client node")
parser.add_argument("-m", "--memory", dest='memory', type=int, default=1024, help="set docker memory for repl client node")
parser.add_argument("-n", "--network", dest='network', type=str, default="testnet1.casperlabs", help="set docker memory for repl client node")
args = parser.parse_args()

client = docker.from_env()

# Pre clean-up
subprocess.call(["docker", "container", "rm", "-f", "casperlabs-repl.{}".format(args.network)], stderr=subprocess.DEVNULL)

volume = client.volumes.create()

# Delete casperlabs-repl on network if it exists
# result = subprocess.run(["docker", "container", "ls", "--all", "--format", "{{.Names}}"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
# containers = result.stdout.decode('ascii').splitlines()
# for container in containers:
#   if container.endswith(args.network) and "casperlabs-repl" in container:
#     print("removing {}".format(container))
#     result = subprocess.run([ "docker", "container", "rm", "-f", container ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

# Run casperlabs repl loader with replwrap
cmd = "sudo docker run --rm -it -v {}:/var/lib/casperlabs --cpus=.5 --memory=1024m --name casperlabs-repl.{} --network {} io.casperlabs/node --grpc-host node0.testnet1.casperlabs repl".format(volume.name, args.network, args.network)
repl_cmds = ['5', new s(`rho:io:stdout`) in { s!("foo") }']
conn = replwrap.REPLWrapper(cmd, args.prompt, None)
for i in range(args.repeat):
    for repl_cmd in repl_cmds:
        result = conn.run_command(repl_cmd) 
        print("repetition: {} output: {}".format(i, result))

# Post clean-up
subprocess.call(["docker", "container", "rm", "-f", "casperlabs-repl.{}".format(args.network)])
client.volumes.prune()
