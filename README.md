
## Fork information

This is a forked version of the Vert.x project intended to be used before the support for CRaC lands in the official version. The artifact should land on these Maven coordinates:

```
<dependency>
   <groupId>io.github.crac.io.vertx</groupId>
   <artifactId>vertx-core</artifactId>
   <version>[version].CRAC.[update]</version>
</dependency>
```

where `[version]` is a released Vert.x version we are trying to replace, and `[update]` is incrementing version of CRaC-related changes.


## Vert.x Core

This is the repository for Vert.x core.

Vert.x core contains fairly low-level functionality, including support for HTTP, TCP, file system access, and various other features. You can use this directly in your own applications, and it's used by many of the other components of Vert.x.

For more information on Vert.x and where Vert.x core fits into the big picture please see the [website](http://vertx.io).

## Building Vert.x artifacts

```
> mvn package
```

## Running tests

Runs the tests

```
> mvn test
```

Vert.x supports native transport on BSD and Linux, to run the tests with native transport

```
> mvn test -PtestNativeTransport
```

Vert.x supports domain sockets on Linux exclusively, to run the tests with domain sockets

```
> mvn test -PtestDomainSockets
```

Vert.x has a few integrations tests that run a differently configured JVM (classpath, system properties, etc....)
for ALPN, native and logging

```
> vertx verify -Dtest=FooTest # FooTest does not exists, its only purpose is to execute no tests during the test phase
```

## Building documentation

```
> mvn package -Pdocs -DskipTests
```

Open _target/docs/vertx-core/java/index.html_ with your browser


