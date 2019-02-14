# CasperLabs

The open-source CasperLabs project is building a decentralized, economic, censorship-resistant, public compute infrastructure and blockchain. It will host and execute programs popularly referred to as “smart contracts”. It will be trustworthy, scalable, concurrent, with proof-of-stake consensus and content delivery.

(TBD) features project-related tutorials and documentation, project planning information, events calendar, and information for how to engage with this project.

## Running

### Running from source
Please refer to the [Developer guide](DEVELOPER.md) for information on running from source.

### Running from Docker

TBD

### Running from the tar-ball
TBD

### Installing and running on Debian from DEB package
#### Build and run CLI client tool

**Prerequisites for building from source:**
* dpkg-deb
* dpkg-sig
* dpkg-genchanges
* lintian
* fakeroot
* sbt
* JDK >= 8

Execute `sbt client/debian:packageBin`. Resulted `.deb` package will be placed in the `client/target/` directory.

**Prerequisites for installation:**
* openjdk-11-jre-headless
* openssl

Install using `sudo dpkg -i client/target/casperlabs-client-0.0.1.deb`.

After installation run `casperlabs-client -- --help` for printing help message.

### Installing and running on RedHat and Fedora from RPM package
#### Build and run CLI client tool
**Prerequisites for building from source:**
* rpm
* rpm-build
* sbt
* JDK >= 8

Execute `sbt client/rpm:packageBin`. Resulted `.deb` package will be placed in the `client/target/rpm/RPMS/` directory.

**Prerequisites for installation:**
* java-11-openjdk-headless
* openssl

Install using `sudo rpm -U client/target/rpm/RPMS/casperlabs-client-0.0.1.noarch.rpm`.

After installation run `casperlabs-client -- --help` for printing help message.

### Installing and running on macOS via Homebrew

#### Installing Homebrew - https://brew.sh
TBD

## Developer guide

For building of CasperLabs, please refer to the [Developer guide](DEVELOPER.md)

### Caveats

### Filing Issues

TBD

## Acknowledgements
