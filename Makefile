DOCKER_USERNAME ?= io.casperlabs
DOCKER_PUSH_LATEST ?= false
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stripped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -Po $(SEMVER_REGEX) | tail -n 1 | grep -e . || echo $(TAGS_OR_SHA)))

# All rust related source code. If every Rust module depends on everything then transitive dependencies are not a problem.
# But with comm/build.rs compiling .proto to .rs every time we build the timestamps are updated as well, so filter those and depend on .proto instead.
RUST_SRC := $(shell find . -type f -iregex ".*/Cargo\.toml\|.*/src/.*\.rs\|.*\.proto" \
	| grep -v target \
	| grep -v -e ipc.*\.rs)
SCALA_SRC := $(shell find . -type f -iregex '.*/src/.*\.scala\|.*\.sbt')

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
	rm -rf .make


docker-build-all: \
	docker-build/node \
	docker-build/client \
	docker-build/execution-engine

docker-push-all: \
	docker-push/node \
	docker-push/client \
	docker-push/execution-engine

docker-build/node: .make/docker-build/universal/node .make/docker-build/test/node
docker-build/client: .make/docker-build/universal/client
docker-build/execution-engine: .make/docker-build/execution-engine

# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push/node`
docker-push/%: docker-build/%
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):latest $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		docker push $(DOCKER_USERNAME)/$(PROJECT):latest ; \
	fi


cargo-package-all: \
	.make/cargo-package/execution-engine/common \
	.make/cargo-native-packager/execution-engine/comm

# We need to publish the libraries the contracts are supposed to use.
cargo-publish-all: \
	.make/cargo-publish/execution-engine/common

cargo/clean: $(shell find . -type f -name "Cargo.toml" | grep -v target | awk '{print $$1"/clean"}')

%/Cargo.toml/clean:
	cd $* && cargo clean


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
	    -type f -not -iregex ".*/io\.casperlabs\..*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Copy our own code.
	mkdir -p $(STAGE)/.docker/layers/1st
	find $(STAGE)/lib \
	    -type f -iregex ".*/io\.casperlabs\..*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/1st \;
	# Use the Dockerfile to build the project. Has to be within the context.
	cp $(PROJECT)/Dockerfile $(STAGE)/Dockerfile
	docker build -f $(STAGE)/Dockerfile -t $(DOCKER_USERNAME)/$(PROJECT):latest $(STAGE)
	rm -rf $(STAGE)/.docker $(STAGE)/Dockerfile
	mkdir -p $(dir $@) && touch $@


# Dockerize the Execution Engine.
.make/docker-build/execution-engine: \
		execution-engine/Dockerfile \
		execution-engine/comm/target/release/engine-grpc-server
	# Just copy the executable to the container.
	$(eval RELEASE = execution-engine/target/release)
	cp execution-engine/Dockerfile $(RELEASE)/Dockerfile
	docker build -f $(RELEASE)/Dockerfile -t $(DOCKER_USERNAME)/execution-engine:latest $(RELEASE)
	rm -rf $(RELEASE)/Dockerfile
	mkdir -p $(dir $@) && touch $@

# Make a node that has some extras installed for testing.
.make/docker-build/test/node: \
		.make/docker-build/universal/node \
		docker/test-node.Dockerfile
	docker build -f docker/test-node.Dockerfile -t $(DOCKER_USERNAME)/node:test docker
	mkdir -p $(dir $@) && touch $@


# Refresh Scala build artifacts if source was changed.
.make/sbt-stage/%: $(SCALA_SRC)
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/universal:stage
	mkdir -p $(dir $@) && touch $@

test:
	@echo $(RUST_SRC)

# Re-package cargo if any Rust source code changes (to account for transitive dependencies).
.make/cargo-package/%: \
		$(RUST_SRC) \
		.make/install/protoc
	cd $* && cargo update && cargo package
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

# Create .deb, .rpm and .tgz
.make/cargo-native-packager/%: \
		$(RUST_SRC) \
		.make/install/protoc \
		.make/install/cargo-native-packager
	@# target/release/rpmbuild/RPMs/<arch>
	@# `rpm init` will create a .rpm/<MODULE>.spec file where we can define dependencies if we have to,
	@# but the build won't refresh it if it already exists, and trying to init again results in an error,
	@# and if we `--force` it, then it will add a second set of entries to the Cargo.toml file which will make it invalid.
	#FIXME: The following says "error: No such file or directory (os error 2)""
	#cd $* && ([ -d .rpm ] || cargo rpm init) && cargo rpm build -v

	@# Writes to for example CasperLabs/execution-engine/target/debian/engine_comm_0.1.0_amd64.deb
	@# This command has a --no-build paramter which could speed it up. If RPM already built it, we can add it.
	cd $* && cargo deb

	#FIXME: Figure out what --input and --output should be
	#cd $* && cargo-tarball --help

	mkdir -p $(dir $@) && touch $@



# Build the execution engine executable.
execution-engine/target/release/engine-grpc-server: \
		$(RUST_SRC) \
		.make/install/protoc
	cd execution-engine/comm && \
	cargo update && \
	cargo build --release

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
