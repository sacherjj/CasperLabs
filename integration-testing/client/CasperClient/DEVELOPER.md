# Development

All the files matching `*_pb2.py`, `*_pb2_grpc.py` are generated from proto files.Presently, these files are 
not tracked in the repo, so might not see them.  Please run `python run_codegen.py` see the generated python
files.  Please do not modify them otherwise your changes will be overwritten and lost.

Rest of the python files can be modified and committed to git repo.

## Creating a new version

Please increase the version in `setup.py` if you have modified any of the tracked files in `CasperClient` folder.

While bumping up the version number, please follow semantic version rules. You can find the guidelines for semantic
version in official [website](https://semver.org).

