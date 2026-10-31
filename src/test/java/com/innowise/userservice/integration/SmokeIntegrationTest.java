package com.innowise.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves that the integration test infrastructure works: a request travels through the
 * controller, the service and JPA to a real PostgreSQL, reads the time from the test clock and
 * lands in a real Redis. It also proves that the state is reset between tests, which every
 * later integration test relies on.
 */
class SmokeIntegrationTest extends AbstractIntegrationTest {

  private static final String USER_JSON = """
    {
      "name": "Ivan",
      "surname": "Petrov",
      "birthDate": "1990-05-17",
      "email": "Ivan@Example.COM"
    }
    """;

  @Test
  void userIsStoredAndReadBackThroughTheWholeChain() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(USER_JSON))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.email").value("ivan@example.com"),
        jsonPath("$.active").value(true),
        jsonPath("$.createdAt").value("2026-10-16T10:00:00Z"))
      .andReturn();
    long id = idOf(created);

    mockMvc.perform(get("/api/users/{id}", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.id").value(id),
        jsonPath("$.email").value("ivan@example.com"),
        jsonPath("$.cards").isEmpty(),
        jsonPath("$.updatedAt").value("2026-10-16T10:00:00Z"));

    assertAll(
      () -> assertEquals(1, countRows("users")),
      () -> assertEquals("ivan@example.com", jdbcTemplate.queryForObject(
        "SELECT email FROM users WHERE id = ?", String.class, id)));
  }

  @Test
  void readingAUserPutsItIntoRedis() throws Exception {
    long id = createUser("Ivan", "Petrov", "ivan@example.com");
    assertTrue(cacheKeys().isEmpty(), "creating a user must not fill the cache");

    mockMvc.perform(get("/api/users/{id}", id)).andExpect(status().isOk());

    assertEquals(Set.of(userCacheKey(id)), cacheKeys());
  }

  @Test
  void resetStateClearsDatabaseCacheAndClock() throws Exception {
    createUser("Ivan", "Petrov", "ivan@example.com");
    long second = createUser("Anna", "Smirnova", "anna@example.com");
    mockMvc.perform(get("/api/users/{id}", second)).andExpect(status().isOk());
    clock.advance(Duration.ofHours(1));

    resetState();

    assertAll(
      () -> assertEquals(0, countRows("users")),
      () -> assertTrue(cacheKeys().isEmpty()),
      () -> assertEquals(START, clock.instant()),
      // The identity counter restarted as well: the next user gets the id 1, not 3.
      () -> assertEquals(1L, createUser("Oleg", "Ivanov", "oleg@example.com")));
  }
}
