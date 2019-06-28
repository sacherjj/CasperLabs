# Development

All the files matching `*_pb2.py`, `*_pb2_grpc.py` are generated from proto files.Presently, these files are 
not tracked in the repo, so might not see them.  Please run `python run_codegen.py` see the generated python
files.  Please do not modify them otherwise your changes will be overwritten and lost.

Rest of the python files can be modified and committed to git repo.

## Creating a new version

Please increase the version in `setup.py` if you have modified any of the tracked files in `CasperClient` folder.

While bumping up the version number, please follow semantic version rules. You can find the guidelines for semantic
version in the official [website](https://semver.org).

## Generating a new package and uploading to PyPi

You need to install twine package with `pip install twine` and after that create the source distribution with
`python setup.py sdist`. You can upload the package to PyPi with `twine upload dist/*`. Please ask in `#testing`
channel or mail to `testing@casperlabs.io` for credentials.

Please make sure everything is alright before uploading to PyPi otherwise you cannot override any existing versions
even after deleting them in PyPi. This is a security protection and guarantee from PyPi guys.

Enjoy hacking away. :)
