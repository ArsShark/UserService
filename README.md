# User Service

User management microservice of the course e-commerce application
(Innowise Java Advanced). Part of a system with Authentication Service,
API Gateway, Order Service and Payment Service.

## Planned stack

- Java 21
- Spring Boot 3.4+
- PostgreSQL
- Redis
- Liquibase
- Docker

## Requirements

- JDK 21
- Maven Wrapper is included in the project

## Environment variables

| Variable      | Description                     | Example |
|---------------|---------------------------------|---------|
| `SERVER_PORT` | Application server port         | `8082`  |
| `LOG_DIR`     | Directory for application logs  | `logs`  |

By default, the application runs on port `8082`.

## Build and verify

To build the project and run verification:

```bash
.\mvnw verify
```
Requires Docker: Testcontainers starts PostgreSQL and Redis for the tests

## Tests

All tests need Docker: Testcontainers starts PostgreSQL 18 and Redis 7 on random ports, so
no local database or Redis is required, and a local one is not touched.

```bash
.\mvnw test
```

Useful variations:

```bash
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

## Run the application
To start the application locally:
```bash
.\mvnw spring-boot:run
```
The service will be available at: http://localhost:8082

| Variable          | Description                              | Example           |
|-------------------|------------------------------------------|-------------------|
| `SERVER_PORT`     | Application server port                  | `8082`            |
| `LOG_DIR`         | Directory for application logs           | `logs`            |
| `DB_HOST`         | PostgreSQL host                          | `localhost`       |
| `DB_PORT`         | PostgreSQL port                          | `5433`            |
| `DB_NAME`         | Database name                            | `user_service_db` |
| `DB_USERNAME`     | Database user (required, no default)     | `user_service`    |
| `DB_PASSWORD`     | Database password (required, no default) | not stored in Git |
| `REDIS_HOST`      | Redis host                               | `localhost`       |
| `REDIS_PORT`      | Redis port                               | `6379`            |
| `CACHE_USERS_TTL` | Lifetime of a cached user                | `10m`             |

## Redis

The service caches users together with their cards in Redis. Redis is optional at
runtime: if it is down, requests fall back to the database (a failed cache operation
is logged and waits at most 0.5 s). Entries expire after `CACHE_USERS_TTL`.

To run Redis locally in Docker:

```bash
docker run -d --name user-service-redis -p 6379:6379 redis:7
```
