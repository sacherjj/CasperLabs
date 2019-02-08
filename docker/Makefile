# Don't remove directories created as dependencies.
.SECONDARY:

# Create a new node environment by linking to the template docker-complse.yml file.
node-%:
	$(eval N = $*)

	$(eval KEY_FILE_COUNT = $(shell find .casperlabs -type f -name *.sk | wc -l))
	[ "$(N)" -lt "$(KEY_FILE_COUNT)" ] || (echo "Can't find keyfile for node-$(N)" && exit 1)
	$(eval KEY_FILE = $(shell find .casperlabs -type f -name *.sk | sort | tail -n +$$((1+$(N))) | head -n 1))

	mkdir node-$(N)
	$(eval ENV = node-$(N)/.env)

	@# Create an .env file to hold template variables for docker-compose.
	echo NODE_NUMBER=$(N) > $(ENV)

	@# The name of the file is the public key. Content is the private key.
	echo VALIDATOR_PUBLIC_KEY=$(shell echo "$(KEY_FILE)" | awk -F '[/.]' '{print $$4}') >> $(ENV)
	echo VALIDATOR_PRIVATE_KEY=$(shell cat "$(KEY_FILE)") >> $(ENV)

	@# Link Dockerfile so we can change it and reapply.
	ln -s ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml

# Start node.
node-%/up: node-% .make/docker/network
	@# Go into the directory to pick up values from .env
	cd node-$* && docker-compose up -d

# Tear down node.
node-%/down:
	[ -d node-$* ] && cd node-$* && docker-compose down || exit 0
	rm -rf node-$*

# Remove all node-N environments.
clean: $(shell find . -type d -name "node-*" | awk -F '/' '{print $$2"/down"}')
	docker network rm casperlabs
	rm -rf .make


.make/docker/network:
	docker network create casperlabs
	mkdir -p $(dir $@) && touch $@