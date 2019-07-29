# Installation

## Download
Check our public repositories with prebuilt binaries:

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
