# User Service

[![CI](https://github.com/ArsShark/UserService/actions/workflows/ci.yml/badge.svg)](https://github.com/ArsShark/UserService/actions/workflows/ci.yml)

User management microservice of the course e-commerce application
(Innowise Java Advanced). Part of a system with Authentication Service,
API Gateway, Order Service and Payment Service.

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data JPA, Spring Cache)
- Spring Security: OAuth 2.0 resource server, JWT (RS256) issued by the Authentication Service
- PostgreSQL 18 with Liquibase migrations
- Redis 7 (cache)
- MapStruct, Log4j2
- JUnit 5, Mockito, Testcontainers
- Docker and Docker Compose

## Requirements

- JDK 21
- Docker (tests, local PostgreSQL and Redis, running the container)
- The Authentication Service on `http://localhost:8081` for every request that carries a token
  (the tests do not need it: they serve a test key set themselves)
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
| `INTERNAL_API_KEY`       | Key of the internal endpoints (required, no default; the application does not start without it) | - | - |
| `AUTH_JWKS_URI`          | Address of the key set of the Authentication Service | `http://localhost:8081/.well-known/jwks.json` | `http://authentication-service:8081/.well-known/jwks.json` |
| `JWT_ISSUER`             | Expected issuer of the tokens            | `authentication-service` | `authentication-service` |

The local port `5433` keeps a local PostgreSQL container away from a PostgreSQL installed on the host.

## Security

The service is an OAuth 2.0 resource server. It never signs a token: it verifies the **access
token** of the Authentication Service (`Authorization: Bearer ...`) with the public key that it
reads from `AUTH_JWKS_URI`.

- **What is checked:** the RS256 signature, the issuer (`JWT_ISSUER`), the time (no clock skew,
  `exp` is required, the application clock is used) and `token_type = access`, so a refresh token
  never works. The claim `role` (`ADMIN` or `USER`, exactly) becomes the authority; the claim
  `userId` is the id of the caller's profile and decides the ownership of resources.
- **The key set** is fetched on the first token, not at startup, and cached for 5 minutes. A
  fetch has a 2 second timeout and a 64 KB limit, and an unknown `kid` may cause at most one
  refresh per 30 seconds, so a flood of forged tokens cannot make this service call the
  Authentication Service for every request. If the Authentication Service is down, tokens that
  match the cached key keep working; anything that cannot be checked is refused with the same
  401 as a bad token (never a 500).
- **Closed by default:** every path and method is listed in `SecurityConfig`; everything else is
  denied (401 for an anonymous caller, 403 for an authenticated one, never 404). There are no
  sessions, cookies or CSRF tokens.
- **Errors:** RFC 9457 `application/problem+json` with constant texts: 401
  `Authentication is required`, 403 `Access is denied`. The answer never says why a token was
  refused.

### Who may call what

| Endpoint | `ADMIN` | `USER` | internal key |
|----------|---------|--------|--------------|
| `POST /api/users`, `GET /api/users` | yes | no | no |
| `GET /api/users/{id}` | yes | own profile | no |
| `PUT /api/users/{id}` | yes | own profile, **the email cannot be changed** | no |
| `PATCH /api/users/{id}/status`, `DELETE /api/users/{id}` | yes | no | no |
| `POST`, `GET /api/users/{userId}/cards` | yes | own cards | no |
| `GET /api/cards` | yes | no | no |
| `GET`, `PUT`, `DELETE /api/cards/{id}`, `PATCH /api/cards/{id}/deactivate` | yes | own cards | no |
| `PATCH /api/cards/{id}/activate` | yes | no (an admin may have blocked the card) | no |
| `POST /internal/users`, `DELETE /internal/users/{id}` | no | no | yes |

- Somebody else's **profile** is a 403 (no database access, the answer does not depend on whether
  the id exists). Somebody else's **card** is the same 404 as a card that does not exist, so a
  caller cannot find out which card ids are taken. A `USER` token without `userId` owns nothing.
- `/internal/**` is for other services (the Gateway registers a user and rolls the profile back).
  It needs the header `X-Internal-Key` with the value of `INTERNAL_API_KEY`, compared in constant
  time; a missing and a wrong key give the same 401. The Gateway does not route this prefix, so
  it is not reachable from outside. The key opens nothing else.

### Known limits

- A token stays valid until it expires (15 minutes), even if the profile is deactivated or deleted
  in the meantime: this service does not know about the lifecycle of accounts. The Authentication
  Service does not know about it either; the Gateway or an event would have to keep them in step.
- There is one signing key and no rotation: a new key invalidates all tokens.
- The rollback of a registration deletes only the profile. The Authentication Service has no
  delete for accounts on purpose.

## Run the application

### Locally (profile `local`)

Start PostgreSQL and Redis in Docker (once), then the application:

```powershell
$env:DB_USERNAME = "user_service"
$env:DB_PASSWORD = "<choose a password>"
$env:INTERNAL_API_KEY = "<the same value as in the Authentication Service>"

docker run -d --name user-service-db `
  -e POSTGRES_USER=$env:DB_USERNAME -e POSTGRES_PASSWORD=$env:DB_PASSWORD -e POSTGRES_DB=user_service_db `
  -p 5433:5432 -v user-service-db-data:/var/lib/postgresql postgres:18
docker run -d --name user-service-redis -p 6379:6379 redis:7

.\mvnw spring-boot:run
```

The service will be available at: http://localhost:8082. A request with a token needs the
Authentication Service on port 8081 (the key set is fetched on the first token).

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
  -e AUTH_JWKS_URI=http://host.docker.internal:8081/.well-known/jwks.json `
  -e DB_USERNAME -e DB_PASSWORD -e INTERNAL_API_KEY `
  user-service:dev
```

This example uses the PostgreSQL and Redis containers from the previous section.
`-e DB_USERNAME` without a value passes the variable from the current shell, so the password
stays out of the command line.

### With Docker Compose

`docker-compose.yml` starts the application (built from this repository), PostgreSQL 18 and Redis 7.

```powershell
Copy-Item .env.example .env
# set DB_PASSWORD and INTERNAL_API_KEY in .env (the file is ignored by Git)
docker compose up --build -d
docker compose ps
```

The application is available at http://localhost:8082. PostgreSQL and Redis are reachable only
inside the Compose network (their ports are not published), the application waits for their
health checks. Variables set in the shell win over `.env`. The Authentication Service is not in
this file: `.env.example` points `AUTH_JWKS_URI` to the one on the host
(`host.docker.internal:8081`). In the Compose file of the whole system it is the service name.

Useful commands:

```powershell
docker compose logs app --tail 50                 # application log
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose exec redis redis-cli --scan --pattern "user-service:users::*"
docker compose down                                # stop, keep the data
docker compose down -v                             # stop and delete the data volumes
```

## API check with Postman

`postman/UserService_postman_collection.json` (70 requests, 161 checks) is the manual check of the whole
API, through the real Authentication Service. Import it, start PostgreSQL, Redis, the Authentication
Service (port 8081) and this service (port 8082), then set the **current values** of the collection
variables `adminLogin`, `adminPassword` (the first admin of the Authentication Service,
`ADMIN_LOGIN` and `ADMIN_PASSWORD` there) and `internalKey` (`INTERNAL_API_KEY`, the same value
in both services). They are secrets and are not stored in the file.

Run the folders top to bottom with the Collection Runner:

| Folder | What it checks |
|--------|----------------|
| `0. Authentication` | login as the admin; the token is kept in `accessToken` and sent by every request |
| `1.` to `4.` | users and cards as the admin: happy path, validation and errors, the limit of 5 active cards, delete and cascade |
| `5. Access control` | a user created through the internal endpoints: his own resources work, somebody else's are 403 or 404, admin-only endpoints are 403, bad tokens and a refresh token are 401, the internal key and its rollback |

Every run uses unique data and removes the profiles it created. The Authentication Service keeps
the account of the test user (it has no delete): each run adds one account with a unique login.
The access token lives 15 minutes; run the collection again to get a new one.

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
| Controllers and error handling | `controller`, `exception` | `@WebMvcTest` with the real security chain |
| Security | `security`, `config` | plain JUnit; web slices with real tokens and a test key set served over HTTP (`JwksTestServer`), including the outage of the Authentication Service |
| Integration tests | `integration` | the whole application on PostgreSQL and Redis containers, driven by MockMvc; the tokens are verified through a real HTTP key set |

The integration tests extend `AbstractIntegrationTest`. All of them share one Spring context
(and so one pair of containers), therefore they must not use `@MockitoBean`,
`@TestPropertySource` or `@DirtiesContext`. They are not transactional: before every test the
base class empties the tables, flushes Redis and resets a controllable test clock.

The tests never need the Authentication Service: `TestKeys` generates a key pair at runtime (no key
is stored in the repository), `TestTokens` signs tokens like the real service and `JwksTestServer`
serves the key set. `SecretsNotLoggedIntegrationTest` runs the whole API with good and bad tokens,
refused requests and conflicts, and then checks that no token, key, email or card number reached
the log.

`.\mvnw test` also writes a JaCoCo coverage report to `target/site/jacoco/index.html`.

## CI pipeline

The workflow `.github/workflows/ci.yml` (GitHub Actions) runs on every push, on pull requests into
`main` and manually (Actions > CI > Run workflow).

| Job      | Step            | What it does |
|----------|-----------------|--------------|
| `build`  | Build           | `./mvnw -DskipTests package` |
| `build`  | Test            | all tests (unit and integration, Testcontainers) and the JaCoCo report |
| `build`  | Code analysis   | SonarQube Cloud; only on pull requests into `main` and on pushes to `main` |
| `build`  | Upload reports  | JUnit and JaCoCo reports as the artifact `reports` (kept 14 days) |
| `docker` | Build and push  | builds the image from the `Dockerfile` on every run (after `build` succeeded); pushes it to GHCR only on pushes to `main` or on a manual run with `publish` ticked |

The free plan of SonarQube Cloud analyses only `main` and pull requests into `main`, so pushes to
other branches skip the analysis. The quality gate result does not fail the pipeline yet.

Published image (the package must be public to pull it without a login):

```powershell
docker pull ghcr.io/arsshark/userservice:latest      # last build of main
docker pull ghcr.io/arsshark/userservice:sha-<short commit id>
```

It runs like the locally built image, see "In a Docker container".

Repository settings used by the pipeline (Settings > Secrets and variables > Actions):

| Name                   | Kind     | Purpose |
|------------------------|----------|---------|
| `SONAR_TOKEN`          | secret   | token of the SonarQube Cloud project |
| `SONAR_ORGANIZATION`   | variable | SonarQube Cloud organization key |
| `SONAR_PROJECT_KEY`    | variable | SonarQube Cloud project key |

Pushing to GHCR uses the built-in `GITHUB_TOKEN`, no extra secret is needed.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `Connection refused` to `localhost:5433` at startup | The PostgreSQL container is stopped: `docker start user-service-db user-service-redis` |
| `UnknownHostException: postgres` outside Docker | The `docker` profile is active, but there is no Compose network: unset `SPRING_PROFILES_ACTIVE` or set `DB_HOST` |
| `DB_PASSWORD is required` from Compose | `.env` is missing or has no password: copy `.env.example` to `.env` |
| `docker build` asks for 1 argument | The build context is missing: `docker build -t user-service:dev .` (note the dot) |
| The application does not start: `The internal key is not set` | `INTERNAL_API_KEY` is missing or blank: set it (the same value as in the Authentication Service); in Compose it is required in `.env` |
| Every request with a token is 401, the log has `A token could not be verified` | The key set cannot be fetched: the Authentication Service is not running or `AUTH_JWKS_URI` is wrong (from a container `localhost` is the container itself: use `host.docker.internal` or the service name) |
| Every request with a token is 401, nothing in the log | The token is wrong: expired (15 minutes), a refresh token, another issuer (`JWT_ISSUER`) or signed by another key |
| `403` for an admin on a path that looks right | The path and method are not in `SecurityConfig`: everything unlisted is denied |
| Port 8082 is already in use | Stop the locally running application or the other container |
| CI: `Not authorized` or `Project not found` in "Code analysis" | `SONAR_TOKEN`, `SONAR_ORGANIZATION` or `SONAR_PROJECT_KEY` is missing or wrong; names must match exactly |
| CI: "You are running CI analysis while Automatic Analysis is enabled" | Turn off Automatic Analysis in the SonarQube Cloud project (Administration > Analysis Method) |
| CI: `denied` when pushing the image | Settings > Actions > General > Workflow permissions must allow the job to write packages |
| The application starts, but the first request is slow with Redis down | Expected: a failed cache operation waits up to 0.5 s, then the database is used |
