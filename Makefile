DOCKER_USERNAME ?= io.casperlabs
DOCKER_PUSH_LATEST ?= false
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stipped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -Po $(SEMVER_REGEX) | tail -n 1 | grep -e . || echo $(TAGS_OR_SHA))) 


# Build the `latest` docker image for local testing. Works with Scala.
docker-build-universal-%:
	$(eval PROJECT = $*)
	$(eval STAGE = $(PROJECT)/target/universal/stage)
	[ -d "$(STAGE)" ] || (echo "Build the artifacts first!" && exit 1)
	rm -rf $(STAGE)/.docker
	# Copy the 3rd party dependencies to a separate directory so if they don't change we can push faster.
	mkdir -p $(STAGE)/.docker/layers/3rd
	find $(STAGE)/lib \
	    -type f ! -iregex ".*/io.casperlabs.*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Copy our own code.
	mkdir -p $(STAGE)/.docker/layers/1st
	find $(STAGE)/lib \
	    -type f -iregex ".*/io.casperlabs.*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Use the Dockerfile to build the project. Has to be within the context.
	cp $(PROJECT)/Dockerfile $(STAGE)/Dockerfile
	docker build -f $(STAGE)/Dockerfile -t $(DOCKER_USERNAME)/$(PROJECT):latest $(STAGE)
	rm -rf $(STAGE)/.docker $(STAGE)/Dockerfile


# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push-node`
docker-push-universal-%:
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):latest $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		echo docker push $(DOCKER_USERNAME)/$(PROJECT):latest ; \
	fi


docker-build-node: docker-build-universal-node
docker-build-client: docker-build-universal-client
docker-push-node: docker-push-universal-node
docker-push-client: docker-push-universal-client
