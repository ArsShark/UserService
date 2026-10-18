# User Service

User management microservice of the course e-commerce application
(Innowise Java Advanced). Part of a system with Authentication Service,
API Gateway, Order Service and Payment Service.

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data JPA, Spring Cache)
- PostgreSQL 18 with Liquibase migrations
- Redis 7 (cache)
- MapStruct, Log4j2
- JUnit 5, Mockito, Testcontainers
- Docker and Docker Compose

## Requirements

- JDK 21
- Docker (tests, local PostgreSQL and Redis, running the container)
- Maven Wrapper is included in the project

## Configuration

### Spring profiles

| Profile  | How it is selected                                     | Config file               | PostgreSQL and Redis hosts by default |
|----------|--------------------------------------------------------|---------------------------|---------------------------------------|
| `local`  | default, used when no profile is set                   | `application-local.yml`   | `localhost`                           |
| `docker` | `SPRING_PROFILES_ACTIVE=docker` (set by Docker Compose) | `application-docker.yml`  | `postgres` and `redis` (Compose service names) |

The profile files contain only connection defaults (datasource URL, Redis host and port).
Credentials are never stored in them. Every value can be overridden by an environment variable.

### Environment variables

| Variable                 | Description                              | Default in `local` | Default in `docker` |
|--------------------------|------------------------------------------|--------------------|---------------------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile                    | `local` (default profile) | set to `docker` by Compose |
| `SERVER_PORT`            | Application server port                  | `8082`             | `8082`              |
| `LOG_DIR`                | Directory for application logs           | `logs`             | `logs` (`/app/logs`) |
| `DB_HOST`                | PostgreSQL host                          | `localhost`        | `postgres`          |
| `DB_PORT`                | PostgreSQL port                          | `5433`             | `5432`              |
| `DB_NAME`                | Database name                            | `user_service_db`  | `user_service_db`   |
| `DB_USERNAME`            | Database user (required, no default)     | -                  | -                   |
| `DB_PASSWORD`            | Database password (required, no default) | -                  | -                   |
| `REDIS_HOST`             | Redis host                               | `localhost`        | `redis`             |
| `REDIS_PORT`             | Redis port                               | `6379`             | `6379`              |
| `CACHE_USERS_TTL`        | Lifetime of a cached user                | `10m`              | `10m`               |

The local port `5433` keeps a local PostgreSQL container away from a PostgreSQL installed on the host.

## Run the application

### Locally (profile `local`)

Start PostgreSQL and Redis in Docker (once), then the application:

```powershell
$env:DB_USERNAME = "user_service"
$env:DB_PASSWORD = "<choose a password>"

docker run -d --name user-service-db `
  -e POSTGRES_USER=$env:DB_USERNAME -e POSTGRES_PASSWORD=$env:DB_PASSWORD -e POSTGRES_DB=user_service_db `
  -p 5433:5432 -v user-service-db-data:/var/lib/postgresql postgres:18
docker run -d --name user-service-redis -p 6379:6379 redis:7

.\mvnw spring-boot:run
```

The service will be available at: http://localhost:8082

Later runs only need `docker start user-service-db user-service-redis`.
`$env:` variables live only in the current PowerShell tab and must be set again in a new one.
PostgreSQL 18 images keep their data under `/var/lib/postgresql`, so the volume is mounted there.

### In a Docker container

The image is built from the multi-stage `Dockerfile`: the first stage builds the jar with the
Maven Wrapper (tests are skipped, the CI pipeline runs them), the second stage contains only a JRE
and the jar. The application runs as a non-root user and writes its logs to `/app/logs`.

```powershell
docker build -t user-service:dev .

docker run --rm --name user-service -p 8082:8082 `
  -e SPRING_PROFILES_ACTIVE=docker `
  -e DB_HOST=host.docker.internal -e DB_PORT=5433 -e REDIS_HOST=host.docker.internal `
  -e DB_USERNAME -e DB_PASSWORD `
  user-service:dev
```

This example uses the PostgreSQL and Redis containers from the previous section.
`-e DB_USERNAME` without a value passes the variable from the current shell, so the password
stays out of the command line.

### With Docker Compose

`docker-compose.yml` starts the application (built from this repository), PostgreSQL 18 and Redis 7.

```powershell
Copy-Item .env.example .env
# set DB_PASSWORD in .env (the file is ignored by Git)
docker compose up --build -d
docker compose ps
```

The application is available at http://localhost:8082. PostgreSQL and Redis are reachable only
inside the Compose network (their ports are not published), the application waits for their
health checks. Variables set in the shell win over `.env`.

Useful commands:

```powershell
docker compose logs app --tail 50                 # application log
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose exec redis redis-cli --scan --pattern "user-service:users::*"
docker compose down                                # stop, keep the data
docker compose down -v                             # stop and delete the data volumes
```

## Redis

The service caches users together with their cards in Redis. Redis is optional at
runtime: if it is down, requests fall back to the database (a failed cache operation
is logged and waits at most 0.5 s). Entries expire after `CACHE_USERS_TTL`.

## Logs

Log4j2 writes to the console and to `${LOG_DIR}/user-service.log` (rolled daily and at 10 MB,
10 files are kept). In Docker Compose the log directory is the named volume `app-logs`:

```powershell
docker compose exec app ls -la /app/logs
```

## Build and verify

To build the project and run verification:

```powershell
.\mvnw verify
```

Requires Docker: Testcontainers starts PostgreSQL and Redis for the tests.

## Tests

All tests need Docker: Testcontainers starts PostgreSQL 18 and Redis 7 on random ports, so
no local database or Redis is required, and a local one is not touched.

```powershell
.\mvnw test
```

Useful variations:

```powershell
.\mvnw test "-DexcludedGroups=integration"   # everything except the integration tests
.\mvnw "-Dtest=UserServiceTest" test           # one test class
```

| Layer | Package | What it uses |
|-------|---------|--------------|
| Unit tests of the services | `service` | Mockito only, no Spring |
| DTO validation, mappers, cache configuration | `dto`, `mapper`, `config` | plain JUnit or a tiny Spring context |
| Cache wiring of the services | `service` | Spring context with an in-memory cache |
| Repositories | `repository` | `@DataJpaTest` on a PostgreSQL container |
| Controllers and error handling | `controller`, `exception` | `@WebMvcTest` |
| Integration tests | `integration` | the whole application on PostgreSQL and Redis containers, driven by MockMvc |

The integration tests extend `AbstractIntegrationTest`. All of them share one Spring context
(and so one pair of containers), therefore they must not use `@MockitoBean`,
`@TestPropertySource` or `@DirtiesContext`. They are not transactional: before every test the
base class empties the tables, flushes Redis and resets a controllable test clock.

`.\mvnw test` also writes a JaCoCo coverage report to `target/site/jacoco/index.html`.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `Connection refused` to `localhost:5433` at startup | The PostgreSQL container is stopped: `docker start user-service-db user-service-redis` |
| `UnknownHostException: postgres` outside Docker | The `docker` profile is active, but there is no Compose network: unset `SPRING_PROFILES_ACTIVE` or set `DB_HOST` |
| `DB_PASSWORD is required` from Compose | `.env` is missing or has no password: copy `.env.example` to `.env` |
| `docker build` asks for 1 argument | The build context is missing: `docker build -t user-service:dev .` (note the dot) |
| Port 8082 is already in use | Stop the locally running application or the other container |
| The application starts, but the first request is slow with Redis down | Expected: a failed cache operation waits up to 0.5 s, then the database is used |
