# Local full-stack containers

This is a private, single-host stack for Docker Engine with the Compose plugin, including Docker
Desktop with its engine running. It builds separate web, game and chat images and runs two PostgreSQL
services. It uses its own Compose project (`world-jigsaw-local-stack`) and named volumes, separate
from the root development Compose file and its databases. Check `docker compose ls` first if you
already have a project with that name. No existing database is imported or modified by this setup.

From the repository root:

```sh
docker compose -f deploy/local/compose.yml up --build -d
docker compose -f deploy/local/compose.yml ps
```

Open <http://127.0.0.1:8088/> on the Docker host. The first build downloads dependencies and may
take time. The web port is published on host loopback only; the game, chat and databases have no
published ports. Docker Desktop's dashboard should show the project and its five containers once
started. On Docker Desktop, the engine and volumes are managed in its Linux VM; on native Linux
they are managed by the local Docker engine. Docker Desktop must be running before these commands.

```text
Browser on this computer
        | 127.0.0.1:8088
        v
Web / gateway ---- loopback ---- game ---- game-db ---- game-db-data volume
        |                         |  ^
        |                writer-lock | session identity
        +------ loopback ---- chat --+---- chat-db ---- chat-db-data volume
```

The web, game and chat containers share a network namespace, while the databases are reached by
service name on the Compose network. The writer-lock volume belongs to the game, not chat. This
topology is for one computer: it does not support distributing the services across three computers
or connecting browsers on other computers. That requires a separate design for identity, secure
transport, access control and single-writer ownership; changing the published address alone is not
sufficient. Kubernetes is deferred.

Docker's documentation explains [Compose networking](https://docs.docker.com/compose/how-tos/networking/),
[health-based startup order](https://docs.docker.com/compose/how-tos/startup-order/), and
[Docker Desktop networking](https://docs.docker.com/desktop/features/networking/).

The gateway, game and chat share one network namespace so their existing loopback-only service and
identity checks still apply. The gateway preserves the browser Host and Origin, upgrades game
WebSockets and does not buffer chat streams. Game and chat use separate private databases. The
credentials in this local-only configuration are example credentials, not secrets. This prototype
has no public authentication or security review; do not publish it on a wider interface or treat
its private network as a public deployment boundary. Run only one copy of the game against its
database, on this host, with the shared writer-lock volume intact. Do not scale or replace the lock
volume while a game process is running. If the web container is recreated, recreate game and chat
as well, because they share its network namespace.

A new database has no puzzle. To import the bundled synthetic fixture for today, first ensure the
stack is running and the game database is healthy. Check `curl -i http://127.0.0.1:8088/api/v1/game/current`;
proceed only if it reports `PUZZLE_UNAVAILABLE` with HTTP 503. An existing board causes the import
to fail and cannot confirm this import succeeded. Stop this stack's game and use a one-off process
with the same database and writer lock:

```sh
docker compose -f deploy/local/compose.yml stop game
docker compose -f deploy/local/compose.yml run --rm --no-deps game \
  --game-import-day="$(date -u +%F)" --game-import-file=/opt/hoi-an-compact-60.json
```

The one-off application keeps serving after import. In another terminal, run the following until it
returns a JSON response whose `snapshot.puzzleId` is `dd4fed6c-6e43-5b70-b814-28ff5d803af6`:

```sh
curl -fsS http://127.0.0.1:8088/api/v1/game/current
```

If the import process exits or reports an error, inspect its output instead of proceeding. Once the
expected puzzle appears, stop the one-off process with Ctrl-C, wait for it to exit, and restart the
normal services:

```sh
docker compose -f deploy/local/compose.yml up -d game chat
```

Import is a deliberate write and fails for an existing day or conflicting puzzle ID. The fixture has
a fixed puzzle ID and cannot be reused for another day in the same database; future days need distinct validated puzzle
definitions. Importing changes the game database; use a disposable database for integration tests.
If no puzzle is imported for the current UTC day, the game reports it unavailable. Chat messages
are ephemeral; chat settings and committed game state live in their respective named volumes.

Use `docker compose -f deploy/local/compose.yml logs game chat` for diagnostics and
`docker compose -f deploy/local/compose.yml stop` to stop this project. Ordinary stop and start
preserve the named volumes. Do not use `down -v` if the data is needed. These volumes provide
persistence on one host; they are not backups.
