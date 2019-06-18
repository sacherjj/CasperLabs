# CasperLabs

The open-source CasperLabs project is building a decentralized, economic, censorship-resistant, public compute infrastructure and blockchain. It will host and execute programs popularly referred to as “smart contracts”. It will be trustworthy, scalable, concurrent, with proof-of-stake consensus and content delivery.

## Download
Check our public repositories with prebuilt binaries:

  http://repo.casperlabs.io/casperlabs/repo/

  https://dl.bintray.com/casperlabs/debian/

  https://dl.bintray.com/casperlabs/rpm/

## Installing from debian repository
```
echo "deb https://dl.bintray.com/casperlabs/debian /" | sudo tee -a /etc/apt/sources.list.d/casperlabs.list
curl -o casperlabs-public.key.asc https://bintray.com/user/downloadSubjectPublicKey?username=casperlabs
sudo apt-key add casperlabs-public.key.asc
sudo apt update
sudo apt install casperlabs
```

## Installing from rpm repository
```
curl https://bintray.com/casperlabs/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-casperlabs-rpm.repo
sudo yum install casperlabs
```

## Running

### Running from source
Please refer to the [Developer guide](DEVELOPER.md) for information on running from source.

### Running from Docker

Please refer to the [docker guide](hack/docker/README.md) for information on running from docker.

### Running from the tar-ball

Artifacts are published to http://repo.casperlabs.io/casperlabs/repo

You can run from the packaged archive for example as follows:

*NOTE: Users will need to update \[VERSION\] with the version the want.

```console
$ ARCHIVE=http://repo.casperlabs.io/casperlabs/repo/master/casperlabs-node-[VERSION].tgz
$ curl -s -o casperlabs-node.tgz $ARCHIVE
$ tar -xzf casperlabs-node.tgz
$ ./casperlabs-node-[VERSION]/bin/casperlabs-node --version
Casper Labs Node
```

### Installing and running on Debian from DEB package
#### CLI client tool
##### Build from sources

**Prerequisites for building from source:**
* dpkg-deb
* dpkg-sig
* dpkg-genchanges
* lintian
* fakeroot
* sbt
* JDK >= 8

Execute `sbt client/debian:packageBin`. Resulted `.deb` package will be placed in the `client/target/` directory.

##### Installation

**Prerequisites for installation:**
* openjdk-11-jre-headless
* openssl

Install using `sudo dpkg -i client/target/casperlabs-client-[VERSION].deb`.

After installation run `casperlabs-client -- --help` for printing help message.

### Installing and running on RedHat and Fedora from RPM package
#### CLI client tool
##### Build from sources
**Prerequisites for building from source:**
* rpm
* rpm-build
* sbt
* JDK >= 8

Execute `sbt client/rpm:packageBin`. Resulted `.deb` package will be placed in the `client/target/rpm/RPMS/` directory.

##### Installation

**Prerequisites for installation:**
* java-11-openjdk-headless
* openssl

Install using `sudo rpm -U client/target/rpm/RPMS/noarch/casperlabs-client-[VERSION].noarch.rpm`.

After installation run `casperlabs-client -- --help` for printing help message.

## Developer guide

For building of CasperLabs, please refer to the [Developer guide](DEVELOPER.md)

## Validator guide

For running a CasperLabs node, please refer to the [Validator guide](VALIDATOR.md)
