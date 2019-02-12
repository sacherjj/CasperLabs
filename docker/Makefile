CL_CASPER_NUM_VALIDATORS ?= 10

# Don't remove directories created as dependencies.
.SECONDARY:

# Create a new node environment by linking to the template docker-complse.yml file.
node-%: .casperlabs/genesis
	$(eval N = $*)

	$(eval KEY_FILE_COUNT = $(shell find .casperlabs -type f -name *.sk | wc -l))
	[ "$(N)" -lt "$(KEY_FILE_COUNT)" ] || (echo "Can't find keyfile for node-$(N)" && exit 1)
	$(eval KEY_FILE = $(shell find .casperlabs -type f -name *.sk | sort | tail -n +$$((1+$(N))) | head -n 1))

	mkdir node-$(N)
	$(eval ENV = node-$(N)/.env)

	@# Create an .env file to hold template variables for docker-compose.
	echo NODE_NUMBER=$(N) > $(ENV)

	@# The name of the file is the public key. Content is the private key.
	echo CL_VALIDATOR_PUBLIC_KEY=$(shell echo "$(KEY_FILE)" | awk -F '[/.]' '{print $$4}') >> $(ENV)
	echo CL_VALIDATOR_PRIVATE_KEY=$(shell cat "$(KEY_FILE)") >> $(ENV)

	@# Link Dockerfile so we can change it and reapply.
	ln -s ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml

# Start node.
node-%/up: node-% .make/docker/network
	@# Go into the directory to pick up values from .env
	cd node-$* && docker-compose up -d

# Tear down node.
node-%/down:
	if [ -d node-$* ]; then \
		cd node-$* && docker-compose down && cd - && \
		rm -rf node-$* ; \
	fi

# Remove all node-N environments.
clean: $(shell find . -type d -name "node-*" | awk -F '/' '{print $$2"/down"}')
	docker network rm casperlabs || exit 0
	@# Removing genesis created by docker, due to permissions.
	docker run --rm \
		-v ${PWD}/.casperlabs:/root/.casperlabs \
		--entrypoint sh \
		io.casperlabs/node:latest \
		-c "rm -rf /root/.casperlabs/genesis"
	rm -rf .make


.make/docker/network:
	docker network create casperlabs
	mkdir -p $(dir $@) && touch $@

# Generate keys and bonds.
.casperlabs/genesis:
	@# Start a node to generate keys.
	@# The node will fail before it would print the ID corresponding to the
	@# keys as there's no gRPC socket to connect to, so the bootstrap keys
	@# and node ID are still hardcoded.
	docker run --rm \
		-v ${PWD}/.casperlabs/genesis:/root/.casperlabs/genesis \
		io.casperlabs/node:latest \
		run -s --casper-num-validators $(CL_CASPER_NUM_VALIDATORS) \
		> /dev/null 2>&1 || exit 0
	[ -f .casperlabs/genesis/bonds.txt ]