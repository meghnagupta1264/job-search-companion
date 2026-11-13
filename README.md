# Job Search Companion

Personal job application tracker. The React/REST foundation and local MongoDB replica set are configured; tracking features and login are not implemented yet.

## Prerequisites

- Java 21 (Maven Wrapper is included)
- Node.js 26 and npm
- Colima, Docker CLI and Compose for the MongoDB setup in step 2

## Run locally

From the repository root, start the database before the backend:

```sh
# Start the local container runtime if it is stopped.
colima start
# Initialize the replica set and wait until it can accept writes.
docker compose up -d --wait
```

Then start the backend:

```sh
./mvnw spring-boot:run
```

In another terminal, start the frontend:

```sh
cd frontend
npm ci
npm run dev
```

Open http://127.0.0.1:5173. The connection card should show `Job Search Companion: UP`.
Vite proxies `/api` to http://127.0.0.1:8080, so no cross-origin configuration is needed.
Both development servers bind to localhost. Stop either with Ctrl+C.

`GET /api/health` reports application availability, not database readiness. MongoDB readiness is checked by Compose; the health page remains independent of database availability. All other backend routes require authentication. Account login will be implemented in step 3; CSRF protection remains enabled.

## Verify

```sh
./mvnw verify
cd frontend
npm run build
npm run lint
```

## Layout

- `src/main/java/com/example/webapp/config`: backend configuration
- `src/main/java/com/example/webapp/system`: application availability endpoint
- `frontend/src/api`: frontend HTTP functions and response types
- `frontend/src`: React UI and routing

The frontend build currently outputs to `frontend/dist`; serving it through Spring Boot will be configured in the packaging stage. Account login and CSV import are not configured yet.

## MongoDB (local development)

The pinned MongoDB image runs a single-member replica set named `rs0`. Transactions need a replica set; one member provides transaction support, not high availability. The named `mongodb-data` volume preserves data when containers are restarted or recreated.

This laptop-only setup has no database authentication. Only loopback port `127.0.0.1:27017` is published; do not expose it on a network. Other processes on your laptop can access it. A shared or hosted environment will require authentication and a separate configuration.

Connect from MongoDB Compass using:

```text
mongodb://localhost:27017/job_search_companion?replicaSet=rs0
```

Collections and their domain indexes will be created as the features are implemented. An empty database may not appear in Compass until the first record is written. Override `MONGODB_URI` to use another environment; Spring Boot 4 reads `spring.mongodb.uri`.

```sh
# Check readiness and initialization logs.
docker compose ps -a
docker compose logs mongodb-init
# Verify actual commits and rollbacks in a separate test database.
./mvnw verify -Pmongo-integration
# Stop containers while retaining records.
docker compose stop
# Resume; initialization also works with an existing replica set.
docker compose up -d --wait
```

Avoid `docker compose down -v`: it deletes the persistent database volume. Database files are inside Colima's virtual disk, not the source directory. Colima/VM deletion can also remove them; backup and restore tooling will be added in the daily-use stage.
