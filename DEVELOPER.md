## Developer's Guide to Building and Running CasperLabs

__Note__ Successfully building from source requires attending to all of the prerequisites shown below. When users experience errors, it is typically related to failure to assure all prerequisites are met. Work is in progress to improve this experience.

### Prerequisites

* [OpenJDK](https://openjdk.java.net) Java Development Kit (JDK), version 11. We recommend using the OpenJDK
* [sbt](https://www.scala-sbt.org/download.html)
* [rust](https://www.rust-lang.org/tools/install)
* [protoc](https://github.com/protocolbuffers/protobuf/releases)

### CasperLabs Development Environment on Ubuntu and Debian

<details>
  <summary>Click to expand!</summary>

  Java:
  ```console
  dev@dev:~$ sudo apt install openjdk-11-jdk -y
  ...

  dev@dev:~/CasperLabs$ java -version
  openjdk version "10.0.2" 2018-07-17
  OpenJDK Runtime Environment (build 10.0.2+13-Ubuntu-1ubuntu0.18.04.4)
  OpenJDK 64-Bit Server VM (build 10.0.2+13-Ubuntu-1ubuntu0.18.04.4, mixed mode)
  ```

  sbt:
  ```console
  dev@dev:~/CasperLabs$ echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
  dev@dev:~/CasperLabs$ sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
  dev@dev:~/CasperLabs$ sudo apt-get update
  dev@dev:~/CasperLabs$ sudo apt-get install sbt -y

  dev@dev:~$ sbt sbtVersion
  [info] Loading project definition from /home/dev/project
  [info] Set current project to dev (in build file:/home/dev/)
  [info] 1.2.8
  ```

  rust:

  Install packages needed for installing and building Rust.
  ```console
  dev@dev:~$ sudo apt install build-essential cmake curl -y
  ```

  Install Rust
  ```console
  dev@dev:~$ curl https://sh.rustup.rs -sSf | sh
  ...
  1) Proceed with installation (default)
  2) Customize installation
  3) Cancel installation
  >1
  ...
  Rust is installed now. Great!

  To get started you need Cargo's bin directory ($HOME/.cargo/bin) in your PATH
  environment variable. Next time you log in this will be done automatically.

  To configure your current shell run source $HOME/.cargo/env

  dev@dev:~$ source $HOME/.cargo/env

  dev@dev:~$ rustup update
  info: syncing channel updates for 'stable-x86_64-unknown-linux-gnu'
  info: checking for self-updates

    stable-x86_64-unknown-linux-gnu unchanged - rustc 1.32.0 (9fda7c223 2019-01-16)

  dev@dev:~$ rustup toolchain install nightly
  info: syncing channel updates for 'nightly-x86_64-unknown-linux-gnu'
  info: latest update on 2019-01-23, rust version 1.33.0-nightly (4c2be9c97 2019-01-22)
  ...

    nightly-x86_64-unknown-linux-gnu installed - rustc 1.33.0-nightly (4c2be9c97 2019-01-22)

  dev@dev:~$ rustup target add wasm32-unknown-unknown --toolchain nightly
  info: downloading component 'rust-std' for 'wasm32-unknown-unknown'
   10.0 MiB /  10.0 MiB (100 %)   2.6 MiB/s ETA:   0 s
  info: installing component 'rust-std' for 'wasm32-unknown-unknown'
  ```

  protoc:
  ```console
  dev@dev:~$ PROTOC_VERSION=3.6.1
  dev@dev:~$ PROTOC_ZIP=protoc-$PROTOC_VERSION-linux-x86_64.zip
  dev@dev:~$ curl -OL https://github.com/google/protobuf/releases/download/v$PROTOC_VERSION/$PROTOC_ZIP
    % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                   Dload  Upload   Total   Spent    Left  Speed
  100   164    0   164    0     0    301      0 --:--:-- --:--:-- --:--:--   300
  100   619    0   619    0     0    923      0 --:--:-- --:--:-- --:--:--   923
  100 1390k  100 1390k    0     0   973k      0  0:00:01  0:00:01 --:--:-- 3029k
  dev@dev:~$ sudo unzip -o $PROTOC_ZIP -d /usr/local bin/protoc
  Archive:  protoc-3.6.1-linux-x86_64.zip
    inflating: /usr/local/bin/protoc
  dev@dev:~$ rm -f $PROTOC_ZIP

  dev@dev:~$ protoc --version
  libprotoc 3.6.1
  ```
</details>

### CasperLabs Development Environment on macOS
<details>
  <summary>Click to expand!</summary>

  Brew:
  On a Mac, Homebrew is helpful for installing software. Install it here:
  ```console
  dev@dev:~$ /usr/bin/ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)"
  ```
  Cask is an extension of Brew:
  ```console
  dev@dev:~$ brew tap caskroom/cask
  ```

  Java:
  ```console
  dev@dev:~$ brew cask install java

  dev@dev:~$ java -version
  ```

  sbt:
  ```console
  dev@dev:~$ brew install sbt@1
  ```

  cmake:
  ```console
  dev@dev:~$ brew install cmake
  ```

  Rust:
  ```console
  dev@dev:~$ brew install rustup
  ...

  dev@dev:~$rustup-init
  Welcome to Rust!

  This will download and install the official compiler for the Rust programming
  language, and its package manager, Cargo.

  It will add the cargo, rustc, rustup and other commands to Cargo's bin
  directory, located at:

    /Users/dev/.cargo/bin

  This path will then be added to your PATH environment variable by modifying the
  profile file located at:

    /Users/dev/.profile

  You can uninstall at any time with rustup self uninstall and these changes will
  be reverted.


  Rust is installed now. Great!

  To get started you need Cargo's bin directory ($HOME/.cargo/bin) in your PATH
  environment variable. Next time you log in this will be done automatically.

  To configure your current shell run source $HOME/.cargo/env
  ```

  ```console
  dev@dev:~$ source $HOME/.cargo/env

  dev@dev:~$ rustup update
  ...

  dev@dev:~$ rustup toolchain install nightly
  ...

  dev@dev:~$ rustup target add wasm32-unknown-unknown --toolchain nightly
  ...
  ```

  protoc:
  ```console
  dev@dev:~$ brew install protobuf
  ...

  dev@dev:~$ protoc --version
  ```
</details>

### CasperLabs Development Environment on Fedora
<details>
  <summary>Click to expand!</summary>

  Java:
  ```console
  dev@dev:~$ dnf search openjdk
  ...

  dev@dev:~$ sudo dnf install <java-version>

  dev@dev:~/CasperLabs$ java --version
  openjdk 11.0.1 2018-10-16
  OpenJDK Runtime Environment 18.9 (build 11.0.1+13)
  OpenJDK 64-Bit Server VM 18.9 (build 11.0.1+13, mixed mode, sharing)
  ```

  sbt:
  ```console
  dev@dev:~/CasperLabs$ curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
  dev@dev:~/CasperLabs$ dnf repolist
  Last metadata expiration check: 0:40:08 ago on Thu 31 Jan 2019 02:04:54 PM EST.
  repo id                                      repo name                                                         status
  bintray--sbt-rpm                             bintray--sbt-rpm                                                      37
  ...

  dev@dev:~/CasperLabs$ sudo dnf --enablerepo=bintray--sbt-rpm install sbt

  dev@dev:~$ sbt sbtVersion
  [info] Loading project definition from /home/dev/project
  [info] Set current project to dev (in build file:/home/dev/)
  [info] 1.2.8
  ```

  ```console
  dev@dev:~/CasperLabs$ sudo dnf install cmake
  ```

  rust:
  ```console
  dev@dev:~$ curl https://sh.rustup.rs -sSf | sh
  ...
  1) Proceed with installation (default)
  2) Customize installation
  3) Cancel installation
  >1
  ...
  Rust is installed now. Great!

  To get started you need Cargo's bin directory ($HOME/.cargo/bin) in your PATH
  environment variable. Next time you log in this will be done automatically.

  To configure your current shell run source $HOME/.cargo/env

  dev@dev:~$ source $HOME/.cargo/env

  dev@dev:~$ rustup update
  info: syncing channel updates for 'stable-x86_64-unknown-linux-gnu'
  info: checking for self-updates

    stable-x86_64-unknown-linux-gnu unchanged - rustc 1.32.0 (9fda7c223 2019-01-16)

  dev@dev:~$ rustup toolchain install nightly
  info: syncing channel updates for 'nightly-x86_64-unknown-linux-gnu'
  info: latest update on 2019-01-23, rust version 1.33.0-nightly (4c2be9c97 2019-01-22)
  ...

    nightly-x86_64-unknown-linux-gnu installed - rustc 1.33.0-nightly (4c2be9c97 2019-01-22)

  dev@dev:~$ rustup target add wasm32-unknown-unknown --toolchain nightly
  info: downloading component 'rust-std' for 'wasm32-unknown-unknown'
   10.0 MiB /  10.0 MiB (100 %)   2.6 MiB/s ETA:   0 s
  info: installing component 'rust-std' for 'wasm32-unknown-unknown'
  ```

  protoc:
  ```console
  dev@dev:~$ PROTOC_VERSION=3.6.1
  dev@dev:~$ PROTOC_ZIP=protoc-$PROTOC_VERSION-linux-x86_64.zip
  dev@dev:~$ curl -OL https://github.com/google/protobuf/releases/download/v$PROTOC_VERSION/$PROTOC_ZIP
    % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                   Dload  Upload   Total   Spent    Left  Speed
  100   164    0   164    0     0    301      0 --:--:-- --:--:-- --:--:--   300
  100   619    0   619    0     0    923      0 --:--:-- --:--:-- --:--:--   923
  100 1390k  100 1390k    0     0   973k      0  0:00:01  0:00:01 --:--:-- 3029k
  dev@dev:~$ sudo unzip -o $PROTOC_ZIP -d /usr/local bin/protoc
  Archive:  protoc-3.6.1-linux-x86_64.zip
    inflating: /usr/local/bin/protoc
  dev@dev:~$ rm -f $PROTOC_ZIP

  dev@dev:~$ protoc --version
  libprotoc 3.6.1
  ```
</details>

### CasperLabs Development Environment on ArchLinux

You can use `pacaur` or other AUR installer instead of [`trizen`](https://github.com/trizen/trizen).
```
TBD
```

Once the above prerequistes are installed for your environment, you are ready to build.

### How to build components

#### Build Wasm contracts:
Contracts are in a separate github repo: https://github.com/CasperLabs/contract-examples.
Clone this locally.

Make commands should build in the root of this repo.  `make all` or `make hello-name`.

If make fails, contract can be manually built:

  1. Go to contract directory that interests you (where Cargo.toml is defined), such as `cd hello-name/call`.
  2. To compile Rust contract to Wasm, run  `cargo build --release --target wasm32-unknown-unknown`.
  3. This puts `*.wasm` file in the `<root>/target/wasm32-unknown-unknown/release/` directory. We will use this file when deploying a contract.
  4. If `cargo build --release ...`  doesn't work try `cargo +nightly build ...`

#### Building node:

  1. Go to the root directory of CasperLabs.
  2. Run `sbt -mem 5000 node/universal:stage`

#### Building node's client:

  1. Go to the root directory of CasperLabs.
  2. Run `sbt -mem 5000 client/universal:stage`

#### Building Execution Engine:

  1. Go to the `execution-engine` directory in the CasperLabs root dir.
  2. Run `cargo build`

### Running components

For ease of use node assumes a default directory that is `~/.casperlabs/`  You must create this hidden directory: `mkdir ~/.casperlabs`.

#### Run the Execution Engine

In the root of the EE (`execution-engine`), run:

```
cargo run --bin casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock
```

`.caspernode.sock` is default socket file used for IPC communication.

#### Run the node

In the root of CasperLabs.

[Generate secp256r1 key and X.509 certificate firstly](/VALIDATOR.md#setting-up-keys).

```console
./node/target/universal/stage/bin/casperlabs-node run -s \
    --tls-certificate node.certificate.pem \
    --tls-key secp256r1-private-pkcs8.pem


### Deploying data

In the root of CasperLabs, run:

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port 40401 deploy --from 3030303030303030303030303030303030303030303030303030303030303030 --gas-price 1 --session <contract wasm file> --payment <payment wasm file>
```

At the moment, payment wasm file is not used so use the same file as for the `--session`.

After deploying contract it's not yet executed (its effects are not applied to the global state), so you have to `propose` a block. Run:

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port-internal 40402 propose
```

For the demo purposes we have to propose contracts separately because second contract (`hello-name/call`) won't see the changes (in practice it won't be able to call `hello-name/define` contract) from the previous deploy.

Note that propose uses a different port then the deploy because it's considered an operator-only function, it's not part of the same interface.

### Visualising DAG state

In the root of the node, run:

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port 40401 vdag --depth 10 --out test.png
```

The output will be saved into the `test.png` file.

It's also possible subscribing to DAG changes and view them in the realtime

```
./client/target/universal/stage/bin/casperlabs-client --host 127.0.0.1 --port 40401 vdag --depth 10 --out test.png --stream=multiple-outputs
```

The outputs will be saved into the files `test_0.png`, `test_1.png`, etc.

For more information and possible output image formats check the help message

```
./client/target/universal/stage/bin/casperlabs-client vdag --help
```

## Information for developers

Assure prerequisites shown above are met.

### Developer Quick-Start

When working in a single project, scope all `sbt` commands to that project. The most effective way is to maintain a running `sbt` instance, invoked from the project root:

```
dev@dev:~/CasperLabs$ sbt
[info] Loading settings for project casperlabs-build from plugins.sbt ...
[info] Loading project definition from /home/dev/CasperLabs/project
[info] Loading settings for project casperlabs from build.sbt ...
[info] Set current project to casperlabs (in build file:/home/dev/CasperLabs/)
[info] sbt server started at local:///home/dev/.sbt/1.0/server/0415e009bb0a13209                                                                                                                                                             cb0/sock
sbt:casperlabs> project node
[info] Set current project to node (in build file:/home/dev/CasperLabs/)
sbt:node> stage
...
[info] Done packaging.
[success] Total time: 113 s, completed Jan 22, 2019, 10:53:37 PM
```

but single-line commands work, too:

```
$ sbt "project node" clean compile test
```

or

```
$ sbt node/clean node/compile node/test
```

### Building

The build is organized into several, mostly autonomous projects. These projects may be built (and used!) on their own, or they may be combined together to form the full node package. The build process in any case is contained in and controlled by a single, top-level `build.sbt` file. This process is able to produce several different kinds of artifacts, including JAR files (for Scala) and Docker images.

The most up-to-date code is found in the `dev` branch. This brilliant, cutting-edge source is periodically merged into `master`, which branch should represent a more stable, tested version.

#### Whole-project Build

Please check the prerequistes on your machine if the code generation fails.
Then build the whole project with all submodules:

```
> sbt compile
```

#### Packaging

To publish a docker image to your local repo run:

```
> sbt node/docker:publishLocal
[... output snipped ...]
[info] Step 8/8 : ENTRYPOINT ["\/bin\/main.sh"]
[info]  ---> Running in 2ac7f835192d
[info] Removing intermediate container 2ac7f835192d
[info]  ---> 5e79e6d92528
[info] Successfully built 5e79e6d92528
[info] Tagging image 5e79e6d92528 with name: casperlabs/node
[success] Total time: 35 s, completed May 24, 2018 10:19:14 AM
```

Check the local docker repo:

```
> docker images
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
casperlabs/node   latest              5e79e6d92528        7 minutes ago       143MB
<none>              <none>              e9b49f497dd7        47 hours ago        143MB
openjdk             8u151-jre-alpine    b1bd879ca9b3        4 months ago        82MB
```

To deploy a tarball run:

```
> sbt node/universal:packageZipTarball
```

The tarball can be found in directory `node/target/universal/`

#### Running

```
TBD
```

Now after you've done some local changes and want to test them, simply run the last command `tbd` again. It will kill the running app and start a new instance containing latest changes in a completely new forked JVM.

### Cross-developing for Linux (e.g. Ubuntu) on a Mac

You will need a virtual machine running the appropriate version of Linux.

1. Install [VirtualBox]( https://www.virtualbox.org/wiki/Downloads)
2. Install the Linux distribution you need (e.g. [Ubuntu](http://releases.ubuntu.com/16.04/ubuntu-16.04.4-server-amd64.iso))
3. Start VirtualBox and create a new virtual machine in the manager
4. Boot your virtual machine using the Linux distribution ISO installed in step 2.
5. Configure your Linux VM as desired. You may need to install additional tools sucah as g++, g++-multilib, make, git, etc.

For a more convenient experience, you can share a folder on your Mac with the virtual machine. To do this you will need to install the VirtualBox Guest Additions. Unfortunately there are some gotchas with this. You may need to utilize one of these [solutions](https://askubuntu.com/questions/573596/unable-to-install-guest-additions-cd-image-on-virtual-box).

### Running benchmarks

Currently, only `BlockStore` and `DAGStore` benchmarks are done.
The recommended way to run them is to use `sbt` as follows:
```
 sbt "project blockStorage" "jmh:run -i 10 -wi 10 -f2 -t4"
```
This tells `sbt` to run `JMH` benchmarks from the `blockStorage` project with the following parameters:
- 10 warmup iterations
- 10 measuring iterations
- 2 forks
- 4 threads

This procedure requires machine with at least 32GB RAM.
This is a recommended way to run benchmarks.

There is also a properties file which could be use to configure some values: `block-storage/src/test/resources/block-store-benchmark.properties`

For more information about JMH, you can visit JMH project page: http://openjdk.java.net/projects/code-tools/jmh/

## Description of subprojects

### Communication

The [comm](comm) subproject contains code for network related operations.

```
TBD
```
