# CasperLabs

El proyecto de código abierto CasperLabs está construyendo una infraestructura computacional pública 
y una cadena de bloques descentralizada, económica y resistente a la censura. Hospedará y ejecutará 
programas conocidos popularmente como "contratos inteligentes". Será confiable, escalable, concurrente, 
con prueba de consenso y entrega de contenido.

## Descargas

Compruebe nuestro repositorio público con los binarios predefinidos: http://repo.casperlabs.io/casperlabs/repo/

## Ejecución

### Ejecutando desde la fuente
Consulte la [guía del desarrollador](DEVELOPER.md) para obtener información sobre la ejecución desde la fuente.

### Ejecutando con Docker
Consulte la [guía de docker](docker/README.md) para obtener informacion de la ejecución en docker

### Ejecutando desde tar-ball

Los archivos se publican en http://repo.casperlabs.io/casperlabs/repo

Puede ejecutar desde el archivo comprimido empaquetado, por ejemplo, como se indica a continuación:

```console
$ ARCHIVE=http://repo.casperlabs.io/casperlabs/repo/dev/casperlabs-node-0.0.tgz
$ curl -s -o casperlabs-node.tgz $ARCHIVE
$ tar -xzf casperlabs-node.tgz
$ ./casperlabs-node-0.0/bin/casperlabs-node --version
Casper Labs Node 0.0
```

### Instalando y ejecutando en Debian desde el paquete DEB
#### Herramienta CLI para el cliente 
##### Construyendo desde la fuente 

**Prerequisitos para la contrucción desde la fuente**
* dpkg-deb
* dpkg-sig
* dpkg-genchanges
* lintian
* fakeroot
* sbt
* JDK >= 8

Ejecutando `sbt client/debian:packageBin`. Deriva un paquete `.deb` estará situado en el directorio `client/target/`.

## Instalación

**Prerequisitos para la instalación**
* openjdk-11-jre-headless
* openssl

Instalar usando `sudo dpkg -i client/target/casperlabs-client-0.0.1.deb`.

Después de la instalación ejecute `casperlabs-client -- --help` para obtener información de ayuda.

### Instalando y ejecutando en RedHat y Fedora desde el paquete RPM
#### Herramienta CLI para el cliente
##### Construyendo desde la fuente
**Prerequisitos para la contrucción desde la fuente**
* rpm
* rpm-build
* sbt
* JDK >= 8

Ejecutar `sbt client/rpm:packageBin`. Obtenemos un paquete `.deb` que estará alojado en el directorio `client/target/rpm/RPMS/`.

##### Instalación

**Prerequisitos para la instalación**
* java-11-openjdk-headless
* openssl

Instalar usando `sudo rpm -U client/target/rpm/RPMS/noarch/casperlabs-client-0.0.1.noarch.rpm`.

Después de la instalación ejecute `casperlabs-client -- --help` para obtener información de ayuda.

## Guía del desarrollador

Para la construcción de CasperLabs, por favor consulte la [guia del desarrollador](DEVELOPER.md)

## Guía del validador

Para ejecutar un nodo de CasperLabs, por favor consulte la [guia del validador](VALIDATOR.md)



