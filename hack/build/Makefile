$(eval MAKE_START_DATA_DIR = $(shell echo $$(pwd)/.make/run/data-dir))

# Blocks until a line appears in a file
#
# Parameters:
# 1) File in MAKE_START_DATA_DIR directory
# 2) Line to wait
#
# call it as '@call(wait_until,some_file.log,A line I'm waiting for)'
# Pay attention it doesn't have a timeout, so will block forever if line doesn't appear.
# To debug possible problems use 'make attach-*' commands below.
define wait_until
	@while ! grep --silent '$(2)' "$(MAKE_START_DATA_DIR)/$(1)"; do \
	sleep 1; done
endef

# Runs the engine and node in the background using incremental compilation.
# 'make start-background-scala-compiler' must be run explicitly before.
start: \
	.make/run/generate-keys \
	.make/run/start

# Runs Bloop server with SBT in background,
# and automatically updates Bloop configuration on SBT build configuration changes.
# To stop them run 'make stop-background-scala-compiler'.
# Make sure Bloop version at least 1.4.0-RC1 installed https://scalacenter.github.io/bloop/setup
start-background-scala-compiler: .make/run/background-scala-compiler

.make/run/background-scala-compiler:
	@echo "Starting background sbt..."
	@echo "// Automatically reloads sbt on build files changes" > project/reload.sbt
	@echo "// Created by the 'make start'" >> project/reload.sbt
	@echo "Global / onChangedBuildSource := ReloadOnSourceChanges" >> project/reload.sbt
	@sbt -mem 1024 '~bloopInstall' &> "$(MAKE_START_DATA_DIR)/sbt.log" &
	$(call wait_until,sbt.log,Monitoring source files for casperlabs)
	@echo "Starting bloop server..."
	@bloop server &> "$(MAKE_START_DATA_DIR)/bloop.log" &
	$(call wait_until,bloop.log,started on address)
	@echo Done
	@mkdir -p $(dir $@) && touch $@

stop-background-scala-compiler:
	@echo "Stopping backgroung sbt..."
	@ps aux | grep bloopInstall | grep -v 'grep' | awk '{print $$2}' | xargs -I _ kill -9 _
	@rm -rf "$(MAKE_START_DATA_DIR)/sbt.log"
	@echo "Stopping bloop server..."
	@bloop ng-stop &> /dev/null || true
	@rm -rf "$(MAKE_START_DATA_DIR)/bloop.log"
	@rm -rf .make/run/background-scala-compiler
	@rm -rf project/reload.sbt
	@echo Done

.make/run/generate-keys:
	@echo "Data directory is $(MAKE_START_DATA_DIR)"
	@echo "Generating keys..."
	@mkdir -p "$(MAKE_START_DATA_DIR)"
	@./hack/key-management/docker-gen-keys.sh "$(MAKE_START_DATA_DIR)"
	@mkdir -p $(dir $@) && touch $@

.make/run/start:
ifneq ($(wildcard .make/run/background-scala-compiler),)
	@echo "Running engine..."
	@echo "" > "$(MAKE_START_DATA_DIR)/engine.log"
	@cd execution-engine/engine-grpc-server && cargo run -- "$(MAKE_START_DATA_DIR)/.casper-node.sock" &> "$(MAKE_START_DATA_DIR)/engine.log" &
	$(call wait_until,engine.log,is listening on socket)
	@echo "Running node..."
	@bloop run node \
	--args run \
	--args "--casper-standalone" \
	--args "--server-host=0.0.0.0" \
	--args "--server-no-upnp" \
	--args "--server-data-dir=$(MAKE_START_DATA_DIR)" \
	--args "--casper-validator-sig-algorithm=ed25519" \
	--args "--casper-validator-public-key-path=$(MAKE_START_DATA_DIR)/validator-public.pem" \
	--args "--casper-validator-private-key-path=$(MAKE_START_DATA_DIR)/validator-private.pem" &> "$(MAKE_START_DATA_DIR)/node.log" &
	$(call wait_until,node.log,Listening for traffic on)
	@echo Done
	@mkdir -p $(dir $@) && touch $@
else
	@echo Can\'t find background Scala compiler to run incremental compilation
	@echo Run \'make start-background-scala-compiler\' first
endif

stop:
	@echo 'Stopping engine...'
	@ps aux | grep cargo | grep -v 'grep' | awk '{print $$2}' | xargs -I _ kill -9 _
	@echo 'Stopping node...'
	@ps aux | grep -i casperlabs | grep bloop | grep run | grep args | grep -v 'grep' | awk '{print $$2}' | xargs -I _ kill -9 _
	@echo 'Cleaning the state...'
	$(shell rm -rf $(MAKE_START_DATA_DIR)/engine.log $(MAKE_START_DATA_DIR)/node.log $(MAKE_START_DATA_DIR)/global_state $(MAKE_START_DATA_DIR)/sqlite* $(MAKE_START_DATA_DIR)/.casper-node.sock)
	@rm -rf .make/run/start
	@echo 'Done'

attach-sbt:
	tail -f "$(MAKE_START_DATA_DIR)/sbt.log"

attach-bloop:
	tail -f "$(MAKE_START_DATA_DIR)/bloop.log"

attach-node:
	tail -f "$(MAKE_START_DATA_DIR)/node.log"

attach-engine:
	tail -f "$(MAKE_START_DATA_DIR)/engine.log"
