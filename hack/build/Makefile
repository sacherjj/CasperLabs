$(eval PROJECT = $(shell echo $$(pwd)/../..))
$(eval MAKE_START_UTIL_DIR = $(shell echo $$(pwd)/.make/run))
$(eval MAKE_START_DATA_DIR = $(shell echo $$(pwd)/.make/run/data-dir))

# Runs the engine and node in the background using incremental compilation.
# 'make start-background-scala-compiler' must be run explicitly before.
start: .make/run/start

# Runs Bloop server with SBT in background,
# and automatically updates Bloop configuration on SBT build configuration changes.
# To stop them both run 'make stop-background-scala-compiler'.
# Make sure Bloop version is at least 1.4.0-RC1 installed https://scalacenter.github.io/bloop/setup
start-background-scala-compiler: .make/run/background-scala-compiler

.make/run/background-scala-compiler: .make/run/sbt-autoreload
	@echo "Starting background sbt..."
	@cd "$(PROJECT)" && sbt -mem 1024 '~bloopInstall' &> "$(MAKE_START_UTIL_DIR)/sbt.log" &
	@while ! grep --silent "Monitoring source files for casperlabs" "$(MAKE_START_UTIL_DIR)/sbt.log"; do sleep 1; done
	@echo "Starting bloop server..."
	@bloop server &> "$(MAKE_START_UTIL_DIR)/bloop.log" &
	@while ! grep -E --silent "(started on address)|(A bloop server is already running in port)" "$(MAKE_START_UTIL_DIR)/bloop.log"; do sleep 1; done
	@echo Done
	@mkdir -p $(dir $@) && touch $@

.make/run/sbt-autoreload:
	@mkdir -p "$(MAKE_START_UTIL_DIR)"
	@mkdir -p "$(MAKE_START_DATA_DIR)"
	@echo "// Automatically reloads sbt on build files changes" > "$(PROJECT)/project/reload.sbt"
	@echo "// Created by the 'make start'" >> "$(PROJECT)/project/reload.sbt"
	@echo "Global / onChangedBuildSource := ReloadOnSourceChanges" >> "$(PROJECT)/project/reload.sbt"
	@mkdir -p $(dir $@) && touch $@

stop-background-scala-compiler:
	@echo "Stopping backgroung sbt..."
	@ps | grep bloopInstall | grep -v 'grep' | awk '{print $$1}' | xargs -I _ kill -9 _ &> /dev/null || true
	@rm -rf "$(MAKE_START_UTIL_DIR)/sbt.log"
	@echo "Stopping bloop server..."
	@bloop ng-stop &> /dev/null || true
	@rm -rf "$(MAKE_START_UTIL_DIR)/bloop.log"
	@rm -rf .make/run/background-scala-compiler
	@rm -rf "$(PROJECT)/project/reload.sbt"
	@echo Done

.make/run/generate-keys:
	@mkdir -p "$(MAKE_START_UTIL_DIR)"
	@mkdir -p "$(MAKE_START_DATA_DIR)"
	@echo "Data directory is $(MAKE_START_DATA_DIR)"
	@echo "Generating keys..."
	@../key-management/docker-gen-keys.sh "$(MAKE_START_DATA_DIR)"
	@mkdir -p $(dir $@) && touch $@

.make/run/start: .make/run/generate-keys
ifneq ($(wildcard .make/run/background-scala-compiler),)
	@mkdir -p "$(MAKE_START_UTIL_DIR)"
	@mkdir -p "$(MAKE_START_DATA_DIR)"
	@echo "Running engine..."
	@echo "" > "$(MAKE_START_UTIL_DIR)/engine.log"
	@cd "$(PROJECT)/execution-engine/engine-grpc-server" && \
		cargo run -- "$(MAKE_START_DATA_DIR)/.casper-node.sock" &> "$(MAKE_START_UTIL_DIR)/engine.log" &
	@while ! grep --silent "listening on socket" "$(MAKE_START_UTIL_DIR)/engine.log"; do sleep 1; done
	@echo "Running node..."
	@cd "$(PROJECT)" && bloop run node \
		--args run \
		--args "--casper-standalone" \
		--args "--server-host=0.0.0.0" \
		--args "--server-no-upnp" \
		--args "--server-data-dir=$(MAKE_START_DATA_DIR)" \
		--args "--casper-validator-sig-algorithm=ed25519" \
		--args "--casper-validator-public-key-path=$(MAKE_START_DATA_DIR)/validator-public.pem" \
		--args "--casper-validator-private-key-path=$(MAKE_START_DATA_DIR)/validator-private.pem" &> "$(MAKE_START_UTIL_DIR)/node.log" &
	@while ! grep --silent -i "listening for traffic on" "$(MAKE_START_UTIL_DIR)/node.log"; do sleep 1; done
	@echo Done
	@mkdir -p $(dir $@) && touch $@
else
	@echo Can\'t find background Scala compiler to run incremental compilation
	@echo Run \'make start-background-scala-compiler\' first
endif

stop:
	@echo 'Stopping engine...'
	@ps | grep engine-grpc-server | grep -v grep | awk '{print $$1}' | xargs -I _ kill -9 _
	@echo 'Stopping node...'
	@ps | grep bloop | grep -v grep | grep run | grep node | grep -i python | awk '{print $$1}' | xargs -I _ kill -9 _
	@echo 'Cleaning the state...'
	@rm -rf "$(MAKE_START_UTIL_DIR)/engine.log"
	@rm -rf "$(MAKE_START_UTIL_DIR)/node.log"
	@rm -rf "$(MAKE_START_DATA_DIR)/global_state"
	@rm -rf "$(MAKE_START_DATA_DIR)/sqlite.db"
	@rm -rf "$(MAKE_START_DATA_DIR)/sqlite.db-shm"
	@rm -rf "$(MAKE_START_DATA_DIR)/sqlite.db-wal"
	@rm -rf "$(MAKE_START_DATA_DIR)/.casper-node.sock"
	@rm -rf .make/run/start
	@echo 'Done'

tail-sbt:
	@tail -f "$(MAKE_START_UTIL_DIR)/sbt.log"

tail-bloop:
	@tail -f "$(MAKE_START_UTIL_DIR)/bloop.log"

tail-node:
	@tail -f "$(MAKE_START_UTIL_DIR)/node.log"

tail-engine:
	@tail -f "$(MAKE_START_UTIL_DIR)/engine.log"

cat-sbt:
	@cat "$(MAKE_START_UTIL_DIR)/sbt.log"

cat-bloop:
	@cat "$(MAKE_START_UTIL_DIR)/bloop.log"

cat-node:
	@cat "$(MAKE_START_UTIL_DIR)/node.log"

cat-engine:
	@cat "$(MAKE_START_UTIL_DIR)/engine.log"
