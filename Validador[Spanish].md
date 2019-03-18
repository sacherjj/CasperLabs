## Guía del validador para ejecutar un nodo de CasperLabs

Los binarios preconfigurados se publican en http://repo.casperlabs.io/casperlabs/repo. A continuación se muestra un 
ejemplo de instalación del nodo en Ubuntu.

### Prerequisitos

* [OpenJDK](https://openjdk.java.net) kit de desarrollo Java (JDK) o entorno de ejecución (JRE), versión 11. 
Nosotros recomendamos usar OpenJDK

```sh
sudo add-apt-repository ppa:openjdk-r/ppa
sudo apt update
sudo apt install openjdk-11-jdk
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

Compruebe que tiene la versión correcta de Java:

```console
$ sudo update-alternatives --config java
There are 3 choices for the alternative java (providing /usr/bin/java).

  Selection    Path                                            Priority   Status
------------------------------------------------------------
  0            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      auto mode
  1            /usr/lib/jvm/java-11-openjdk-amd64/bin/java      1111      manual mode
  2            /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java   1081      manual mode
* 3            /usr/lib/jvm/java-8-oracle/jre/bin/java          1081      manual mode

Press <enter> to keep the current choice[*], or type selection number: 0
update-alternatives: using /usr/lib/jvm/java-11-openjdk-amd64/bin/java to provide /usr/bin/java (java) in auto mode
$ java -version
openjdk version "11.0.1" 2018-10-16
OpenJDK Runtime Environment (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1)
OpenJDK 64-Bit Server VM (build 11.0.1+13-Ubuntu-3ubuntu116.04ppa1, mixed mode, sharing)
```

### Instalando desde el paquete para Debian

El nodo consta de un componente de API que se ejecuta en Java y un motor de ejecución que ejecuta el código WASM 
de las implementaciones. Por el momento, deben iniciarse por separado y configurarse para que se comuniquen entre sí.

```sh
curl -sO http://repo.casperlabs.io/casperlabs/repo/dev/casperlabs-node_0.0_all.deb
curl -sO http://repo.casperlabs.io/casperlabs/repo/dev/casperlabs-engine-grpc-server_0.1.0_amd64.deb
sudo dpkg -i casperlabs-node_0.0_all.deb
sudo dpkg -i casperlabs-engine-grpc-server_0.1.0_amd64.deb
```

Después de estos pasos debería poder ejecutar `casperlabs-node --help` y `casperlabs-engine-grpc-server --help`.

### Configuración de las llaves

Como validador necesitará una clave pública y privada para firmar los bloques, y un certificado SSL para proporcionar 
una comunicación segura con otros nodos de la red. Puede ejecutar el nodo una vez para generarlas por adelantado.

Necesitará crear un directorio para guardar los datos. Por defecto se calcula que será en `~/.casperlabs`, 
pero puede proporcinar otra ubicación con la opción `--server-data-dir`.

```console
$ mkdir casperlabs-node-data
$ casperlabs-node run -s --server-data-dir casperlabs-node-data --casper-num-validators 1
10:40:19.729 [main] INFO  io.casperlabs.node.Main$ - CasperLabs node 0.0 (030bb96133ef9a9c31133ab3371937c2388bf5b9)
10:40:19.736 [main] INFO  io.casperlabs.node.NodeEnvironment$ - Using data dir: /home/aakoshh/projects/casperlabs-node-data
10:40:19.748 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - No certificate found at path /home/aakoshh/projects/casperlabs-node-data/node.certificate.pem
10:40:19.749 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a X.509 certificate for the node
10:40:19.752 [main] INFO  i.c.c.t.GenerateCertificateIfAbsent - Generating a PEM secret key for the node
...
10:40:23.788 [main] INFO  i.c.c.util.comm.CasperPacketHandler$ - Starting in create genesis mode
10:40:24.077 [main] WARN  i.casperlabs.casper.genesis.Genesis$ - Specified bonds file None does not exist. Falling back on generating random validators.
10:40:24.088 [main] INFO  i.casperlabs.casper.genesis.Genesis$ - Created validator 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd with bond 1
...
10:40:24.132 [main] WARN  i.c.casper.ValidatorIdentity$ - No private key detected, cannot create validator identification.
...
10:40:24.944 [main] INFO  io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs://e1af07bebc9f88e399efe816ac06dfc6b82f7783@78.144.212.177?protocol=40400&discovery=40404.
...
^C
$ tree casperlabs-node-data
casperlabs-node-data
├── casperlabs-node.log
├── genesis
│   ├── 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk
│   └── bonds.txt
├── node.certificate.pem
├── node.key.pem
└── tmp
    └── comm
        ├── 20190222104024_8091beb0_packet.bts
        └── 20190222104024_f6a0cda2_packet.bts

3 directories, 7 files
```

Una vez que el servidor empieza a escuchar el tráfico, puedes apagarlo con `Ctrl+C` y ver que ha creado algunos archivos:
* `node.certificate.pem` y `node.key.pem` son los certificados SSL que va a utilizar en posteriores arranques. Corresponden a la 
dirección `casperlabs://e1af07bebc9f88e399efe816ac06dfc6b82f7783@78.144.212.177?protocol=40400&discovery=40404` impreso en los logs, en particular el 
valor `e1af0...783` va al ID del nodo, que es un hash de su clave pública.
* `genesis/09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk` es la clave privada del validador. El valor 
`9c47347...7bd` es la clave pública propia, el contenido del archivo es la clave privada.
* `genesis/bonds.txt` tendrá la clave pública con un valor en ella. En modo normal se obtienen los enlaces del nodo bootstrap y los últimos bloques, sin embargo, actualmente 
el nodo depende de la configuración estática.

__NOTA__: Sus valores serán diferentes de los que se muestran arriba.

### Configuración de la red

Con las claves a mano se puede arrancar de nuevo el nodo, pero esta vez configurarlo para que sea accesible en la red.
[UPnP](https://casperlabs.atlassian.net/wiki/spaces/EN/pages/38928385/Node+Supported+Network+Configuration?atlOrigin=eyJpIjoiOTNmZjI2ZDllYmMxNGM1NmIwMzVjNmRlNTAyNzU2M2QiLCJwIjoiYyJ9)
puede ser capaz de descubrir su IP pública y abrir las reglas del cortafuegos en su router, o puede que tenga que hacerlo manualmente.

Si lo haces manualmente, necesitas encontrar tu dirección IP visible desde el exterior. Tendrá que configurar esto usando la opcion `--server-host <ip>`
para que el nodo se publique en la dirección correcta.

El nodo escuchará en varios puertos; los siguientes son los valores predeterminados, no es necesario especificarlos, pero se muestran con la opción de línea de comandos que puede 
usar para anularlos:
* `--grpc-port 40401`: Puerto para aceptar despliegues.
* `--server-port 40400`: Puerto de comunicación dentro del nodo para el consenso.
* `--server-kademila-port 40404`: Puerto de comunicación dentro del nodo para la detección de nodos.

### Inicio del motor de ejecución

El motor de ejecución se ejecuta como un proceso separado y no está abierto a la red, se comunica con el nodo a través de un socket de archivo UNIX. Si está usando 
Windows tendrá que ejecutarse bajo el subsistema Windows para Linux (WSL).

```console
$ casperlabs-engine-grpc-server casperlabs-node-data/.caspernode.sock
Server is listening on socket: casperlabs-node-data/.caspernode.sock
```

### Inicio del nodo

Tendremos que usar el mismo socket para arrancar el nodo que el que usamos con el motor de ejecución.

Puede iniciar el nodo de dos modos:
* `-s` lo pone en modo autosuficiente, lo que significa que generará un bloque de génesis por sí solo.
* Sin `-s` se debe utilizar la opción `--server-bootstrap` y darle la dirección de otro nodo para obtener 
los bloques y empezar a descubrir otros nodos. La dirección es en forma de `casperlabs://<bootstrap-node-id>@$<bootstrap-node-ip-address>?protocol=40400&discovery=40404`,
pero los puertos pueden ser diferentes, dependiendo de lo que haya configurado el operador del nodo.

```console
$ casperlabs-node \
     --grpc-port 40401 \
     run \
     --server-data-dir casperlabs-node-data \
     --server-port 40400 \
     --server-kademlia-port 40404 \
     --server-bootstrap "<bootstrap-node-address>" \
     --server-host <external-ip-address> \
     --server-no-upnp \
     --casper-validator-public-key 09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd \
     --casper-validator-private-key-path casperlabs-node-data/genesis/09c47347913d75b0edb0338d8f34023220af634afa24ae5a6b93f087e24597bd.sk \
     --grpc-socket casperlabs-node-data/.caspernode.sock
```

### Monitorización
Puede añadir la opción `--metrics-prometheus` en cuyo caso el nodo recogerá las métricas y las pondrá a disposición en
`http://localhost:40403/metrics`.  Puede anular eese puerto con la opción `--server-http-port`.

Para ver cómo un ejemplo de cómo configurar Prometheus y Grafana se puede ver el archivo [docker setup](docker/README.md#monitoring)
o [Prometheus docs](https://prometheus.io/docs/prometheus/latest/getting_started/).
