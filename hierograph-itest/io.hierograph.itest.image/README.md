# io.hierograph.itest.image

A Docker image that ships a pre-parsed [jQAssistant](https://jqassistant.org) store
of the Spring Framework jars and serves it through an embedded Neo4j server.

The Spring jars are scanned and analyzed at build time using the project's root
[`.jqassistant.yml`](../../.jqassistant.yml), and the Hierograph rule plugin
(`io.hierograph.jqassistant.rules`) is baked into the image so its rule groups and
concepts are applied. The store, the analysis config and the resolved jQAssistant
plugin repository are all baked into the image, so the container starts fully
offline — nothing is scanned, analyzed or downloaded at runtime.

## Build

The image build is wired into Maven behind the opt-in `itest` profile (it is not
part of the default build):

```bash
mvn -o -Pitest -pl hierograph-itest/io.hierograph.itest.image package
```

This builds the `io.hierograph.jqassistant.rules` plugin, stages the Docker build
context under `target/docker` (Dockerfile, jar-download script, the root
`.jqassistant.yml` and the rules jar), then runs `docker build` and tags the image
`io.hierograph.itest.image:0.2.0-SNAPSHOT`.

A plain `docker build` must run against that staged context (the Dockerfile pulls
in the rules jar and `.jqassistant.yml`, which only exist there after staging):

```bash
mvn -o -Pitest -pl hierograph-itest/io.hierograph.itest.image prepare-package
docker build -t io.hierograph.itest.image:0.2.0-SNAPSHOT \
  hierograph-itest/io.hierograph.itest.image/target/docker
```

The Spring and jQAssistant versions can be overridden via build args
(`SPRING_VERSION`, `JQA_VERSION`); `RULES_VERSION` is passed automatically from the
project version.

## Start

Run detached, publishing the Neo4j browser (`7474`) and Bolt (`7687`) ports:

```bash
docker run -d --name hierograph-itest \
  -p 7474:7474 -p 7687:7687 \
  io.hierograph.itest.image:0.2.0-SNAPSHOT
```

The server binds to `0.0.0.0` inside the container, so the published ports are
reachable from the host. It stays running until the container is stopped:

```bash
docker stop hierograph-itest && docker rm hierograph-itest
```

## Connect

- **Neo4j browser:** open <http://localhost:7474> and connect to `bolt://localhost:7687`.
  Authentication is disabled (`NO_AUTH`) — leave username/password empty.
- **Bolt (driver / cypher-shell):** connect to `bolt://localhost:7687`, no credentials.

  ```bash
  cypher-shell -a bolt://localhost:7687 --non-interactive "MATCH (n) RETURN count(n);"
  ```

Confirm the server is up:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:7474/   # expect 200
```
