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
