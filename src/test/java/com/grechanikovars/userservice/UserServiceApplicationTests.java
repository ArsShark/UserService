package com.grechanikovars.userservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Smoke test: the whole application context starts against a Testcontainers PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserServiceApplicationTests {

  @Test
  void contextLoads() {
  }
}
