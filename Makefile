DOCKER_USERNAME ?= casperlabs
DOCKER_PUSH_LATEST ?= false
DOCKER_LATEST_TAG ?= latest
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stripped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -oE 'v?[0-9]+(\.[0-9]){1,2}$$' | sed 's/^v//' || echo $(TAGS_OR_SHA)))

# All rust related source code. If every Rust module depends on everything then transitive dependencies are not a problem.
# But with comm/build.rs compiling .proto to .rs every time we build the timestamps are updated as well, so filter those and depend on .proto instead.
RUST_SRC := $(shell find . -type f \( -name "Cargo.toml" -o -wholename "*/src/*.rs" -o -name "*.proto" \) \
	| grep -v target \
	| grep -v -e ipc.*\.rs)
SCALA_SRC := $(shell find . -type f \( -wholename "*/src/*.scala" -o -name "*.sbt" \))
PROTO_SRC := $(shell find protobuf -type f \( -name "*.proto" \))
TS_SRC := $(shell find explorer/ui/src explorer/server/src -type f \( -name "*.ts" -o -name "*.tsx" -o -name "*.scss" -o -name "*.json" \))

RUST_TOOLCHAIN := $(shell cat execution-engine/rust-toolchain)

$(eval DOCKER_TEST_TAG = $(shell if [ -z ${DRONE_BUILD_NUMBER} ]; then echo test; else echo test-DRONE-${DRONE_BUILD_NUMBER}; fi))

# Don't delete intermediary files we touch under .make,
# which are markers for things we have done.
# https://stackoverflow.com/questions/5426934/why-this-makefile-removes-my-goal
.SECONDARY:

# Build all artifacts locally.
all: \
	docker-build-all \
	cargo-package-all

# Push the local artifacts to repositories.
publish: \
	docker-push-all \
	cargo-publish-all

clean: cargo/clean
	sbt clean
	cd explorer/ui && rm -rf node_modules build
	cd explorer/server && rm -rf node_modules dist
	rm -rf .make


docker-build-all: \
	docker-build/node \
	docker-build/client \
	docker-build/execution-engine \
	docker-build/integration-testing \
	docker-build/key-generator \
	docker-build/explorer

docker-push-all: \
	docker-push/node \
	docker-push/client \
	docker-push/execution-engine \
	docker-push/key-generator \
	docker-push/explorer

docker-build/node: .make/docker-build/universal/node .make/docker-build/test/node
docker-build/client: .make/docker-build/universal/client .make/docker-build/test/client
docker-build/execution-engine: .make/docker-build/execution-engine .make/docker-build/test/execution-engine
docker-build/integration-testing: .make/docker-build/integration-testing .make/docker-build/test/integration-testing
docker-build/key-generator: .make/docker-build/key-generator
docker-build/explorer: .make/docker-build/explorer
docker-build/grpcwebproxy: .make/docker-build/grpcwebproxy

# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push/node`
docker-push/%: docker-build/%
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		docker push $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) ; \
	fi


cargo-package-all: \
	.make/cargo-package/execution-engine/common \
	cargo-native-packager/execution-engine/comm \
	package-blessed-contracts

# Drone is already running commands in the `builderenv`, no need to delegate.
cargo-native-packager/%:
	if [ -z "${DRONE_BRANCH}" ]; then \
		$(MAKE) .make/cargo-docker-packager/$* ; \
	else \
		$(MAKE) .make/cargo-native-packager/$* ; \
	fi

# We need to publish the libraries the contracts are supposed to use.
cargo-publish-all: \
	.make/cargo-publish/execution-engine/common

cargo/clean: $(shell find . -type f -name "Cargo.toml" | grep -v target | awk '{print $$1"/clean"}')

%/Cargo.toml/clean:
	cd $* && ([ -d target ] && cargo clean --target-dir target || cargo clean)


# Build the `latest` docker image for local testing. Works with Scala.
.make/docker-build/universal/%: \
		%/Dockerfile \
		.make/sbt-stage/%
	$(eval PROJECT = $*)
	$(eval STAGE = $(PROJECT)/target/universal/stage)
	rm -rf $(STAGE)/.docker
	# Copy the 3rd party dependencies to a separate directory so if they don't change we can push faster.
	mkdir -p $(STAGE)/.docker/layers/3rd
	find $(STAGE)/lib \
	    -type f -not -wholename "*/io.casperlabs.*.jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Copy our own code.
	mkdir -p $(STAGE)/.docker/layers/1st
	find $(STAGE)/lib \
	    -type f -wholename "*/io.casperlabs.*.jar" \
	    -exec cp {} $(STAGE)/.docker/layers/1st \;
	# Use the Dockerfile to build the project. Has to be within the context.
	cp $(PROJECT)/Dockerfile $(STAGE)/Dockerfile
	docker build -f $(STAGE)/Dockerfile -t $(DOCKER_USERNAME)/$(PROJECT):$(DOCKER_LATEST_TAG) $(STAGE)
	rm -rf $(STAGE)/.docker $(STAGE)/Dockerfile
	mkdir -p $(dir $@) && touch $@

# Dockerize the Integration Tests
.make/docker-build/integration-testing: \
		integration-testing/Dockerfile
	$(eval IT_PATH = integration-testing)
	cp -r protobuf $(IT_PATH)/
	docker build -f $(IT_PATH)/Dockerfile -t $(DOCKER_USERNAME)/integration-testing:$(DOCKER_LATEST_TAG) $(IT_PATH)/
	rm -rf $(IT_PATH)/protobuf
	mkdir -p $(dir $@) && touch $@

# Dockerize the Execution Engine.
.make/docker-build/execution-engine: \
		execution-engine/Dockerfile \
		cargo-native-packager/execution-engine/comm \
		$(RUST_SRC)
	# Just copy the executable to the container.
	$(eval RELEASE = execution-engine/target/debian)
	cp execution-engine/Dockerfile $(RELEASE)/Dockerfile
	docker build -f $(RELEASE)/Dockerfile -t $(DOCKER_USERNAME)/execution-engine:$(DOCKER_LATEST_TAG) $(RELEASE)
	rm -rf $(RELEASE)/Dockerfile
	mkdir -p $(dir $@) && touch $@

# Make a node that has some extras installed for testing.
.make/docker-build/test/node: \
		.make/docker-build/universal/node \
		hack/docker/test-node.Dockerfile \
		package-blessed-contracts
	# Add blessed contracts so we can use them in integration testing.
	# For live tests we should mount them from a real source.
	mkdir -p hack/docker/.genesis/blessed-contracts
	tar -xvzf execution-engine/target/blessed-contracts.tar.gz -C hack/docker/.genesis/blessed-contracts
	docker build -f hack/docker/test-node.Dockerfile -t $(DOCKER_USERNAME)/node:$(DOCKER_TEST_TAG) hack/docker
	rm -rf hack/docker/.genesis
	mkdir -p $(dir $@) && touch $@

# Make a test version for the execution engine as well just so we can swith version easily.
.make/docker-build/test/execution-engine: \
		.make/docker-build/execution-engine
	docker tag $(DOCKER_USERNAME)/execution-engine:$(DOCKER_LATEST_TAG) $(DOCKER_USERNAME)/execution-engine:$(DOCKER_TEST_TAG)
	mkdir -p $(dir $@) && touch $@

# Make a test tagged version of client so all tags exist for integration-testing.
.make/docker-build/test/client: \
		.make/docker-build/universal/client
	docker tag $(DOCKER_USERNAME)/client:$(DOCKER_LATEST_TAG) $(DOCKER_USERNAME)/client:$(DOCKER_TEST_TAG)
	mkdir -p $(dir $@) && touch $@

# Make an image to run Python tests under integration-testing.
.make/docker-build/test/integration-testing: \
		.make/docker-build/integration-testing
	docker tag $(DOCKER_USERNAME)/integration-testing:$(DOCKER_LATEST_TAG) $(DOCKER_USERNAME)/integration-testing:$(DOCKER_TEST_TAG)
	mkdir -p $(dir $@) && touch $@

# Make an image for keys generation
.make/docker-build/key-generator: \
	hack/key-management/Dockerfile \
	hack/key-management/gen-keys.sh
	docker build -f hack/key-management/Dockerfile -t $(DOCKER_USERNAME)/key-generator:$(DOCKER_LATEST_TAG) hack/key-management
	mkdir -p $(dir $@) && touch $@


# Make an image to host the Casper Explorer UI and the faucet microservice.
.make/docker-build/explorer: \
		explorer/Dockerfile \
		$(TS_SRC) \
		.make/npm/explorer \
		.make/explorer/contracts
	docker build -f explorer/Dockerfile -t $(DOCKER_USERNAME)/explorer:$(DOCKER_LATEST_TAG) explorer
	mkdir -p $(dir $@) && touch $@

.make/npm/explorer: $(TS_SRC) .make/protoc/explorer
	# CI=false so on Drone it won't fail on warnings (currently about href).
	./hack/build/docker-buildenv.sh "\
			cd explorer/ui     && npm install && CI=false npm run build && cd - && \
			cd explorer/server && npm install && npm run clean:dist && npm run build && cd - \
		"
	mkdir -p $(dir $@) && touch $@

# Generate UI client code from Protobuf.
# Installed via `npm install ts-protoc-gen --no-bin-links --save-dev`
.make/protoc/explorer: \
		.make/install/protoc \
		.make/install/protoc-ts \
		$(PROTO_SRC)
	$(eval DIR_IN = ./protobuf)
	$(eval DIR_OUT = ./explorer/grpc/generated)
	rm -rf $(DIR_OUT)
	mkdir -p $(DIR_OUT)
	# First the pure data packages, so it doesn't create empty _pb_service.d.ts files.
	protoc \
	    -I=$(DIR_IN) \
		--plugin=protoc-gen-ts=./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts \
		--js_out=import_style=commonjs,binary:$(DIR_OUT) \
		--ts_out=service=false:$(DIR_OUT) \
		$(DIR_IN)/google/protobuf/empty.proto \
		$(DIR_IN)/io/casperlabs/casper/consensus/consensus.proto \
		$(DIR_IN)/io/casperlabs/casper/consensus/info.proto \
		$(DIR_IN)/io/casperlabs/casper/consensus/state.proto
	# Now the service we'll invoke.
	protoc \
	    -I=$(DIR_IN) \
		--plugin=protoc-gen-ts=./explorer/grpc/node_modules/ts-protoc-gen/bin/protoc-gen-ts \
		--js_out=import_style=commonjs,binary:$(DIR_OUT) \
		--ts_out=service=true:$(DIR_OUT) \
		$(DIR_IN)/io/casperlabs/node/api/casper.proto
	# Annotations were only required for the REST gateway. Remove them from Typescript.
	for f in $(DIR_OUT)/io/casperlabs/node/api/casper_pb* ; do \
		sed -n '/google_api_annotations_pb/!p' $$f > $$f.tmp ; \
		mv $$f.tmp $$f ; \
	done
	mkdir -p $(dir $@) && touch $@

.make/explorer/contracts: $(RUST_SRC) .make/rustup-update
	# Compile the faucet contract that grants tokens.
	cd explorer/contracts && \
	cargo +$(RUST_TOOLCHAIN) build --release --target wasm32-unknown-unknown
	mkdir -p $(dir $@) && touch $@

.make/docker-build/grpcwebproxy: hack/docker/grpcwebproxy/Dockerfile
	cd hack/docker && docker-compose build grpcwebproxy
	docker tag casperlabs/grpcwebproxy:latest $(DOCKER_USERNAME)/grpcwebproxy:$(DOCKER_LATEST_TAG)
	mkdir -p $(dir $@) && touch $@

.make/client/contracts: build-validator-contracts
	mkdir -p $(dir $@) && touch $@

.make/node/contracts:
	mkdir -p $(dir $@) && touch $@

# Refresh Scala build artifacts if source was changed.
.make/sbt-stage/%: $(SCALA_SRC) .make/%/contracts
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/universal:stage
	mkdir -p $(dir $@) && touch $@


# Re-package cargo if any Rust source code changes (to account for transitive dependencies).
.make/cargo-package/%: \
		$(RUST_SRC) \
		.make/install/protoc
	cd $* && cargo package
	mkdir -p $(dir $@) && touch $@

.make/cargo-publish/%: .make/cargo-package/%
	@#https://doc.rust-lang.org/cargo/reference/publishing.html
	@#After a package is first published to crates.io run `cargo owner --add github:CasperLabs:crate-owners` once to allow others to push.
	@#Cargo returns an error if the package has already been published, so we can't break code, we have to publish a newer version.
	cd $* && \
	RESULT=$$(cargo publish 2>&1) ; \
	CODE=$$? ; \
	if echo $$RESULT | grep -q "already uploaded" ; then \
		echo "already uploaded" && exit 0 ; \
	else \
		echo $$RESULT && exit $$CODE ; \
	fi
	mkdir -p $(dir $@) && touch $@


# Create .rpm and .deb packages natively. `cargo rpm build` doesn't work on MacOS.
.make/cargo-native-packager/%: $(RUST_SRC) .make/install/protoc .make/install/cargo-native-packager
	@# .rpm will be at execution-engine/target/release/rpmbuild/RPMS/x86_64/casperlabs-engine-grpc-server-0.1.0-1.x86_64.rpm
	@# `rpm init` will create a .rpm/<MODULE>.spec file where we can define dependencies if we have to,
	@# but the build won't refresh it if it already exists, and trying to init again results in an error,
	@# and if we `--force` it, then it will add a second set of entries to the Cargo.toml file which will make it invalid.
	cd $* && ([ -d .rpm ] || cargo rpm init) && cargo rpm build
	@# .deb will be at execution-engine/target/debian/casperlabs-engine-grpc-server_0.1.0_amd64.deb
	@# This command has a --no-build parameter which can speed it up becuase the RPM compilation seems compatible.
	cd $* && cargo deb --no-build
	mkdir -p $(dir $@) && touch $@

# Create .rpm and .deb packages with Docker so people using Macs can build images locally too.
# We may need to have .rpm and .deb specific builder images that work with what we want to host it in
# as we seem to get some missing dependencies using the builderenv which didn't happend with Ubuntu.
.make/cargo-docker-packager/%: $(RUST_SRC)
	@# .rpm will be at execution-engine/target/release/rpmbuild/RPMS/x86_64/casperlabs-engine-grpc-server-0.1.0-1.x86_64.rpm
	@# .deb will be at execution-engine/target/debian/casperlabs-engine-grpc-server_0.1.0_amd64.deb
	@# Need to use the same user ID as outside if we want to continue working with these files,
	@# otherwise the user running in docker will own them.
	@# Going to ignore errors with the rpm build here so that we can get the .deb package for docker.
	$(eval USERID = $(shell id -u))
	docker pull $(DOCKER_USERNAME)/buildenv:latest
	docker run --rm --entrypoint sh \
		-v ${PWD}:/CasperLabs \
		$(DOCKER_USERNAME)/buildenv:latest \
		-c "\
		apt-get install sudo ; \
		useradd -u $(USERID) -m builder ; \
		cp -r /root/. /home/builder/ ; \
		chown -R builder /home/builder ; \
		sudo -u builder bash -c '\
			export HOME=/home/builder ; \
			export PATH=/home/builder/.cargo/bin:$$PATH ; \
			cd /CasperLabs/$* ; \
			([ -d .rpm ] || cargo rpm init) && cargo rpm build ; \
			cargo deb \
		'"
	mkdir -p $(dir $@) && touch $@


# Compile contracts that need to go into the Genesis block.
package-blessed-contracts: \
	execution-engine/target/blessed-contracts.tar.gz

# Compile a blessed contract; it will be written for example to execution-engine/target/wasm32-unknown-unknown/release/mint_token.wasm
.make/blessed-contracts/%: $(RUST_SRC) .make/rustup-update
	$(eval CONTRACT=$*)
	cd execution-engine/blessed-contracts/$(CONTRACT) && \
	cargo +$(RUST_TOOLCHAIN) build --release --target wasm32-unknown-unknown --target-dir target
	mkdir -p $(dir $@) && touch $@

# Compile a validator contract;
.make/validator-contracts/%: $(RUST_SRC) .make/rustup-update
	$(eval CONTRACT=$*)
	cd execution-engine/validator-contracts/$(CONTRACT) && \
	cargo +$(RUST_TOOLCHAIN) build --release --target wasm32-unknown-unknown
	mkdir -p $(dir $@) && touch $@

client/src/main/resources/%.wasm: .make/validator-contracts/%
	$(eval CONTRACT=$*)
	cp execution-engine/target/wasm32-unknown-unknown/release/$(CONTRACT).wasm $@

build-validator-contracts: \
	client/src/main/resources/bonding.wasm \
	client/src/main/resources/unbonding.wasm

# Package all blessed contracts that we have to make available for download.
execution-engine/target/blessed-contracts.tar.gz: \
	.make/blessed-contracts/mint-token \
	.make/blessed-contracts/pos
	$(eval ARCHIVE=$(shell echo $(PWD)/$@ | sed 's/.gz//'))
	rm -rf $(ARCHIVE) $(ARCHIVE).gz
	mkdir -p $(dir $@)
	tar -cvf $(ARCHIVE) -T /dev/null
	find execution-engine/blessed-contracts -wholename *.wasm | grep -v /release/deps/ | while read file; do \
		cd $$(dirname $$file); tar -rvf $(ARCHIVE) $$(basename $$file); cd -; \
	done
	gzip $(ARCHIVE)


# Build the execution engine executable. NOTE: This is not portable.
execution-engine/target/release/casperlabs-engine-grpc-server: \
		$(RUST_SRC) \
		.make/install/protoc
	cd execution-engine/comm && \
	cargo build --release

# Get the .proto files for REST annotations for Github. This is here for reference about what to get from where, the files are checked in.
# There were alternatives, like adding a reference to a Maven project called `googleapis-commons-protos` but it had version conflicts.
.PHONY: protobuf/google/api
protobuf/google/api:
	$(eval DIR = protobuf/google/api)
	$(eval SRC = https://raw.githubusercontent.com/googleapis/googleapis/master/google/api)
	mkdir -p $(DIR)
	cd $(DIR) && \
	curl -s -O $(SRC)/annotations.proto && \
	curl -s -O $(SRC)/http.proto

.PHONY: protobuf/google
protobuf/google:
	$(eval DIR = protobuf/google/protobuf)
	$(eval SRC = https://raw.githubusercontent.com/protocolbuffers/protobuf/master/src/google/protobuf)
	mkdir -p $(DIR)
	cd $(DIR) && \
	curl -s -O $(SRC)/empty.proto && \
	curl -s -O $(SRC)/descriptor.proto

# Miscellaneous tools to install once.

.make/install/protoc:
	if [ -z "$$(which protoc)" ]; then \
		curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip ; \
		unzip protoc-3.6.1-linux-x86n_64.zip -d protoc ; \
		mv protoc/bin/* /usr/local/bin/ ; \
		mv protoc/include/* /usr/local/include/ ; \
		chmod +x /usr/local/bin/protoc ; \
		rm -rf protoc* ; \
	fi
	mkdir -p $(dir $@) && touch $@

# Install the protoc plugin to generate TypeScript. Use docker so people don't have to install npm.
.make/install/protoc-ts: explorer/grpc/package.json
	./hack/build/docker-buildenv.sh "\
		cd explorer/grpc && npm install && npm install ts-protoc-gen --no-bin-links --save-dev \
	"
	mkdir -p $(dir $@) && touch $@


.make/install/cargo-native-packager:
	@# Installs fail if they already exist.
	cargo install cargo-rpm || exit 0
	cargo install cargo-deb || exit 0
	cargo install cargo-tarball || exit 0
	mkdir -p $(dir $@) && touch $@

.make/install/rpm:
	if [ -z "$$(which rpmbuild)" ]; then
		# You'll need to isntall `rpmbuild` for `cargo rpm` to work. I'm putting this here for reference:
		sudo apt-get install rpm
	fi
	mkdir -p $(dir $@) && touch $@


.make/rustup-update: execution-engine/rust-toolchain
	rustup update $(RUST_TOOLCHAIN)
	rustup toolchain install $(RUST_TOOLCHAIN)
	rustup target add --toolchain $(RUST_TOOLCHAIN) wasm32-unknown-unknown
	mkdir -p $(dir $@) && touch $@
