package com.grechanikovars.userservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a Redis container for tests and wires it into the Spring context, like
 * {@link TestcontainersConfiguration} does for PostgreSQL.
 *
 * <p>It is a separate configuration on purpose: the repository tests import only the PostgreSQL
 * one and do not need to pay for a Redis container they never use.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

  /**
   * Redis container with the same major version as the local one. The service connection
   * replaces the host and the port of the application, so the tests need no environment
   * variables.
   *
   * @return the container
   */
  @Bean
  @ServiceConnection(name = "redis")
  @SuppressWarnings({"rawtypes", "unchecked"})
  GenericContainer redisContainer() {
    return new GenericContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379);
  }
}
