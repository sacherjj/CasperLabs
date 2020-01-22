## Using Systemd to manange CasperLabs processes

Note: This doc applies to packages installed via a package manager such as apt,
 yum, dnf, etc. However, these methods can still be utilized if you build the 
targets yourself.

With the`deb` and `rpm` packages of the node and execution engine we install
default versions of systemd unit files that users can chose to utilize. Since
the CasperLabs software relies on end-user specific flags (for keypaths,
bootstrap address, etc.), we've opted to give users an *almost* production
ready version of these systemd unit files.

These unit-files by default are stored under `/lib/systemd/system` and we 
recommend that they are copied over to `/etc/systemd/system` so adequate changes
 can be made for use.

The node unit file references an `example-configuration.toml` which should be 
copied and edited to reflect paths for end-user specific changes. The 
`example-configuration.toml` lives under `/etc/casperlabs/` by default.

### Copying files to recommended location for modification

```
cp /lib/systemd/system/casperlabs-engine-grpc-server.service /etc/systemd/system/casperlabs-engine-grpc-server.service
cp /lib/systemd/system/casperlabs-node.service /etc/systemd/system/casperlabs-node.service
cp /etc/casperlabs/example-configuration.toml /etc/casperlabs/prod-configuration.toml
```

Note: You can change the name of the `toml` file to whatever you prefer, just 
be sure to reflect the change in the unitfile.

### End-User Changes

The main files that an end-user would need to update are the `configuration.toml` and `casperlabs-node.service`.

#### Congifiguration Toml

The example configuration toml you copied comes completely commented out and thus 
needs a few tweaks made which are specific to your node before being ready 
to use.

The minimum amount of settings you will want to change to allow you connect as 
a readonly node:
- bootstrap
- data-dir
- socket
- certificate
- key 

As a validator you will want to add a few more:
- validator-public-key-path
- validator-private-key-path

#### CasperLabs Node Unit File

By default this file points to the `example-configuration.toml`. You will want 
to update this in `/etc/systemd/system/casperlabs-node.service` to point 
to the configuration you updated for youself. In the below, case I changed it 
to point to `prod-configuration.toml`:

```
[Unit]
Description=CasperLabs Node
After=network.target casperlabs-engine-grpc-server.service
BindsTo=casperlabs-engine-grpc-server.service

[Service]
ExecStart=/usr/bin/casperlabs-node --config-file=/etc/casperlabs/prod-configuration.toml run --server-data-dir=/var/lib/casperlabs
User=casperlabs
Restart=no

[Install]
WantedBy=multi-user.target
```
