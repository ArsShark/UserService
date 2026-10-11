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

## Run the application
To start the application locally:
```bash
.\mvnw spring-boot:run
```
The service will be available at: http://localhost:8082
