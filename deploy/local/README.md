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
stack is running and the game database is healthy, then stop this stack's game and use a one-off
process with the same database and writer lock:

```sh
docker compose -f deploy/local/compose.yml stop game
docker compose -f deploy/local/compose.yml run --rm --no-deps game \
  --game-import-day="$(date -u +%F)" --game-import-file=/opt/hoi-an-expanded-60.json
docker compose -f deploy/local/compose.yml up -d game chat
```

The import process exits after startup: the application normally keeps serving, so stop it with
Ctrl-C once its startup log confirms success, then run the last command. Import is a deliberate
write and fails for an existing day or conflicting puzzle ID. The fixture has a fixed puzzle ID and
cannot be reused for another day in the same database; future days need distinct validated puzzle
definitions. Importing changes the game database; use a disposable database for integration tests.
If no puzzle is imported for the current UTC day, the game reports it unavailable. Chat messages
are ephemeral; chat settings and committed game state live in their respective named volumes.

Use `docker compose -f deploy/local/compose.yml logs game chat` for diagnostics and
`docker compose -f deploy/local/compose.yml stop` to stop this project. Ordinary stop and start
preserve the named volumes. Do not use `down -v` if the data is needed. These volumes provide
persistence on one host; they are not backups.
