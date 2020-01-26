# Runs the engine and node in the background using incremental compilation.
# 'make start-background-scala-compiler' must be run explicitly before.
start: .make/run/start

# Runs Bloop server with SBT in background and automatically updates Bloop configuration on SBT build configuration changes.
# To stop them both run 'make stop-background-scala-compiler'.
# Make sure Bloop version is at least 1.4.0-RC1 installed https://scalacenter.github.io/bloop/setup.
#
# We could add this task implicitly as a dependency to the 'start', however it may harm proper understanding of the underlying workflow.
# Workflow:
# 1. SBT is run with `~bloopInstall`, it means it will automatically run the `bloopInstall` task whenever `bloopInstall`'s
# source files are changed.
# Sources files for `bloopInstall` are *.sbt build configuration files and *.scala files under `project/` directory.
# When SBT runs `bloopInstall` task, it extracts project's build information into the `.bloop` directory.
# 2. `bloop server` runs and keeps a background Scala compiler, so it has benefits by JVM JIT compilation/optimizations and avoiding JVM's 'cold start'.
# Also, it deduplicates the same requests from multiple clients, saving CPU cycles.
# 3. Bloop client, when used from a directory which contains a `.bloop` directory, able to send requests `bloop run ...`, `bloop compile ...`, `bloop test ...` to the Bloop server.
# 4. We use `bloop run ...` in the `make start` task below run the Scala part of CasperLabs Node.
#
# To stop background SBT and Bloop server run `make stop-background-scala-compiler`.
start-background-scala-compiler: .make/run/background-scala-compiler

.make/run/background-scala-compiler: ../../project/reload.sbt
	@mkdir -p .make/run/data-dir
	@echo "Starting background sbt..."
	@cd ../.. && sbt -mem 1024 '~bloopInstall' &> hack/build/.make/run/sbt.log &
	@sleep 1; while ! grep --silent "Monitoring source files for casperlabs" .make/run/sbt.log; do sleep 1; done
	@echo "Starting bloop server..."
	@bloop server &> .make/run/bloop.log &
	@sleep 1; while ! grep -E --silent "(started on address)|(A bloop server is already running in port)" .make/run/bloop.log; do sleep 1; done
	@echo Done
	@mkdir -p $(dir $@) && touch $@

../../project/reload.sbt:
	@echo "// Automatically reloads sbt on build files changes" > ../../project/reload.sbt
	@echo "// Created by the 'make start'" >> ../../project/reload.sbt
	@echo "Global / onChangedBuildSource := ReloadOnSourceChanges" >> ../../project/reload.sbt

stop-background-scala-compiler:
	@echo "Stopping backgroung sbt..."
	@ps -ax | grep bloopInstall | grep -v 'grep' | awk '{print $$1}' | xargs -I _ kill -9 _ &> /dev/null || true
	@rm -rf .make/run/sbt.log
	@echo "Stopping bloop server..."
	@bloop ng-stop &> /dev/null || true
	@rm -rf .make/run/bloop.log
	@rm -rf .make/run/background-scala-compiler
	@rm -rf ../../project/reload.sbt
	@echo Done

.make/run/generate-keys:
	@mkdir -p .make/run/data-dir
	@echo "Data directory is .make/run/data-dir"
	@echo "Generating keys..."
	@../key-management/docker-gen-keys.sh .make/run/data-dir
	@mkdir -p $(dir $@) && touch $@



.make/run/start: .make/run/generate-keys
# Normally, 'wildcard' function used for making using of wildcards as '*' or '?'.
# If there are no matching files, then it will return empty string.
# Here, we use it only for checking file existence.
# If file doesn't exist, then returned string will be empty and equal to the empty string
# at the right of the comma of the 'ifneq' (if not equal) conditional.
# https://www.gnu.org/software/make/manual/html_node/Wildcard-Function.html
# https://www.gnu.org/software/make/manual/html_node/Conditional-Syntax.html
ifneq ($(wildcard .make/run/background-scala-compiler),)
	@mkdir -p .make/run/data-dir
	@echo "Running engine..."
	@echo "" > .make/run/engine.log
	@cd ../../execution-engine/engine-grpc-server && \
		cargo run -- .make/run/data-dir/.casper-node.sock &> hack/build/.make/run/engine.log &
	@sleep 1; while ! grep --silent "listening on socket" .make/run/engine.log; do sleep 1; done
	@echo "Running node..."
	@cd ../.. && bloop run node \
		--args run \
		--args "--casper-standalone" \
		--args "--server-host=0.0.0.0" \
		--args "--server-no-upnp" \
		--args "--server-data-dir=.make/run/data-dir" \
		--args "--casper-validator-sig-algorithm=ed25519" \
		--args "--casper-validator-public-key-path=.make/run/data-dir/validator-public.pem" \
		--args "--casper-validator-private-key-path=.make/run/data-dir/validator-private.pem" &> hack/build/.make/run/node.log &
	@sleep 1; while ! grep --silent -i "listening for traffic on" .make/run/node.log; do sleep 1; done
	@echo Done
	@mkdir -p $(dir $@) && touch $@
else
	@echo Can\'t find background Scala compiler to run incremental compilation
	@echo Run \'make start-background-scala-compiler\' first
endif

stop:
	@echo 'Stopping engine...'
	@ps -ax | grep engine-grpc-server | grep -v grep | awk '{print $$1}' | xargs -I _ kill -9 _
	@echo 'Stopping node...'
	@ps -ax | grep bloop | grep -v grep | grep run | grep node | grep -i python | awk '{print $$1}' | xargs -I _ kill -9 _
	@echo 'Cleaning the state...'
	@rm -rf .make/run/engine.log
	@rm -rf .make/run/node.log
	@rm -rf .make/run/data-dir/global_state
	@rm -rf .make/run/data-dir/sqlite.db
	@rm -rf .make/run/data-dir/sqlite.db-shm
	@rm -rf .make/run/data-dir/sqlite.db-wal
	@rm -rf .make/run/data-dir/.casper-node.sock
	@rm -rf .make/run/start
	@echo 'Done'

tail-sbt:
	@tail -f .make/run/sbt.log

tail-bloop:
	@tail -f .make/run/bloop.log

tail-node:
	@tail -f .make/run/node.log

tail-engine:
	@tail -f .make/run/engine.log

cat-sbt:
	@cat .make/run/sbt.log

cat-bloop:
	@cat .make/run/bloop.log

cat-node:
	@cat .make/run/node.log

cat-engine:
	@cat .make/run/engine.log
