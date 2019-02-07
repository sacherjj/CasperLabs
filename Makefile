DOCKER_USERNAME ?= io.casperlabs
DOCKER_PUSH_LATEST ?= false
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stripped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -Po $(SEMVER_REGEX) | tail -n 1 | grep -e . || echo $(TAGS_OR_SHA)))

# Don't delete intermediary files we touch under .make,
# which are markers for things we have done.
# https://stackoverflow.com/questions/5426934/why-this-makefile-removes-my-goal
.SECONDARY:

docker-build-all: \
	docker-build/node \
	docker-build/client \
	docker-build/execution-engine

docker-push-all: \
	docker-push/node \
	docker-push/client \
	docker-push/execution-engine

docker-build/node: .make/docker-build/universal/node
docker-build/client: .make/docker-build/universal/client
docker-build/execution-engine: .make/docker-build/execution-engine

# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push/node`
docker-push/%:
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):latest $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		docker push $(DOCKER_USERNAME)/$(PROJECT):latest ; \
	fi

clean:
	sbt clean
	cd execution-engine/comm && cargo clean
	rm -rf .make


# Build the `latest` docker image for local testing. Works with Scala.
.make/docker-build/universal/%: .make/sbt-stage/%
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


.make/docker-build/execution-engine: execution-engine/comm/target/release/engine-grpc-server
	# Just copy the executable to the container.
	$(eval RELEASE = execution-engine/comm/target/release)
	cp execution-engine/Dockerfile $(RELEASE)/Dockerfile
	docker build -f $(RELEASE)/Dockerfile -t $(DOCKER_USERNAME)/execution-engine:latest $(RELEASE)
	rm -rf $(RELEASE)/Dockerfile
	mkdir -p $(dir $@) && touch $@


# Refresh Scala build artifacts if source was changed.
.make/sbt-stage/%: .make \
		$(shell find . -type f -iregex '.*/src/.*\.scala\|.*\.sbt')
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/universal:stage
	mkdir -p $(dir $@) && touch $@


.make/rust-proto: .make .make/rustup-update .make/protoc-install \
		$(shell find . -type f -iregex '.*\.proto')
	cd execution-engine/comm && \
	cargo run --bin grpc-protoc
	touch $@


execution-engine/comm/target/release/engine-grpc-server: .make/rust-proto \
		$(shell find . -type f -iregex '.*/src/.*\.rs')
	cd execution-engine/comm && \
	cargo build --release

# Miscellaneous tools to install once.

.make:
	mkdir .make

.make/rustup-update: .make
	rustup update
	rustup toolchain install nightly
	rustup target add wasm32-unknown-unknown --toolchain nightly
	touch $@

.make/protoc-install: .make
	if [ -z "$$(which protoc)" ]; then \
		curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip ; \
		unzip protoc-3.6.1-linux-x86n_64.zip -d protoc ; \
		mv protoc/bin/* /usr/local/bin/ ; \
		mv protoc/include/* /usr/local/include/ ; \
		chmod +x /usr/local/bin/protoc ; \
		rm -rf protoc* ; \
	fi
	touch $@
