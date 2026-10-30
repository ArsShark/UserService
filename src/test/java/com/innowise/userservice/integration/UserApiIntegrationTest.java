package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end scenarios of the user API: a real HTTP-level request goes through the controller,
 * the service and JPA to PostgreSQL, with Redis behind the read path.
 *
 * <p>The scenarios are few and long on purpose. The HTTP contract of every endpoint is already
 * covered by the controller tests and the queries by the repository tests; what is checked here
 * is the behavior that only shows when all layers work together.
 */
class UserApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  void userLifecycleKeepsAuditingTimestampsCorrect() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Petrov", "ivan@example.com")))
      .andExpect(status().isCreated())
      .andReturn();
    long id = idOf(created);
    assertEquals(String.format("http://localhost/api/users/%d", id),
      created.getResponse().getHeader(HttpHeaders.LOCATION));

    clock.advance(Duration.ofHours(1));
    mockMvc.perform(put("/api/users/{id}", id)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Sidorov", "ivan.sidorov@example.com")))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.surname").value("Sidorov"),
        jsonPath("$.email").value("ivan.sidorov@example.com"),
        jsonPath("$.createdAt").value(hoursAfterStart(0)),
        jsonPath("$.updatedAt").value(hoursAfterStart(1)));

    // Deactivation is a bulk update that bypasses JPA Auditing, so the service sets updatedAt.
    clock.advance(Duration.ofHours(1));
    mockMvc.perform(patch("/api/users/{id}/deactivate", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(false),
        jsonPath("$.createdAt").value(hoursAfterStart(0)),
        jsonPath("$.updatedAt").value(hoursAfterStart(2)));

    clock.advance(Duration.ofHours(1));
    mockMvc.perform(patch("/api/users/{id}/activate", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(true),
        jsonPath("$.updatedAt").value(hoursAfterStart(3)));

    // The stored state, read back after all the cache evictions.
    mockMvc.perform(get("/api/users/{id}", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.surname").value("Sidorov"),
        jsonPath("$.active").value(true),
        jsonPath("$.updatedAt").value(hoursAfterStart(3)));

    mockMvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/users/{id}", id))
      .andExpectAll(status().isNotFound(), problemJson());
    assertEquals(0, countRows("users"));
  }

  @Test
  void emailIsUniqueIgnoringCase() throws Exception {
    long first = createUser("Ivan", "Petrov", "ivan@example.com");
    long second = createUser("Anna", "Smirnova", "anna@example.com");

    mockMvc.perform(post("/api/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Oleg", "Ivanov", "IVAN@Example.COM")))
      .andExpectAll(status().isConflict(), problemJson());
    mockMvc.perform(put("/api/users/{id}", second)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Anna", "Smirnova", "Ivan@Example.com")))
      .andExpectAll(status().isConflict(), problemJson());
    // A user may write its own email in another case: it is the same address.
    mockMvc.perform(put("/api/users/{id}", first)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Petrov", "IVAN@example.com")))
      .andExpectAll(status().isOk(), jsonPath("$.email").value("ivan@example.com"));

    assertAll(
      () -> assertEquals(2, countRows("users")),
      () -> assertEquals("anna@example.com", jdbcTemplate.queryForObject(
        "SELECT email FROM users WHERE id = ?", String.class, second)));
  }

  @Test
  void userAndCardsHaveIndependentActiveFlags() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    long firstCard = createCard(userId, cardNumber(1));
    long secondCard = createCard(userId, cardNumber(2));

    mockMvc.perform(patch("/api/users/{id}/deactivate", userId)).andExpect(status().isOk());
    mockMvc.perform(get("/api/users/{id}", userId))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(false),
        jsonPath("$.cards[?(@.active == true)]").value(hasSize(2)));

    mockMvc.perform(patch("/api/users/{id}/activate", userId)).andExpect(status().isOk());
    mockMvc.perform(patch("/api/cards/{id}/deactivate", firstCard)).andExpect(status().isOk());
    mockMvc.perform(get("/api/users/{id}", userId))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(true),
        jsonPath("$.cards[?(@.id == %d)].active", firstCard).value(contains(false)),
        jsonPath("$.cards[?(@.id == %d)].active", secondCard).value(contains(true)));
  }

  @Test
  void deletingAUserDeletesItsCardsOnly() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    long ivanCard = createCard(ivan, cardNumber(1));
    createCard(ivan, cardNumber(2));
    createCard(ivan, cardNumber(3));
    long annaCard = createCard(anna, cardNumber(4));

    mockMvc.perform(delete("/api/users/{id}", ivan)).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/cards/{id}", ivanCard))
      .andExpectAll(status().isNotFound(), problemJson());
    mockMvc.perform(get("/api/cards/{id}", annaCard)).andExpect(status().isOk());
    assertEquals(1, countRows("payment_cards"));
  }

  @Test
  void usersListFiltersByNameAndSurnameIgnoringCase() throws Exception {
    createSampleUsers();

    mockMvc.perform(get("/api/users").param("name", "iVA"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(2),
        jsonPath("$.content[*].surname").value(contains("Petrov", "Sidorov")));
    mockMvc.perform(get("/api/users").param("surname", "PETROV"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(2),
        jsonPath("$.content[*].surname").value(contains("Petrov", "Petrova")));
    // Both filters together narrow the result: they are combined with AND.
    mockMvc.perform(get("/api/users").param("name", "ivan").param("surname", "petrov"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(1),
        jsonPath("$.content[*].email").value(contains("ivan.petrov@example.com")));
    mockMvc.perform(get("/api/users").param("name", "zzz"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(0),
        jsonPath("$.content").isEmpty());
  }

  @Test
  void usersListIsPagedAndSorted() throws Exception {
    createSampleUsers();

    // Surnames in descending order: Smirnova, Sidorov, Petrova, Petrov, Ivanov.
    mockMvc.perform(get("/api/users")
        .param("size", "2")
        .param("page", "1")
        .param("sort", "surname,desc"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.page").value(1),
        jsonPath("$.size").value(2),
        jsonPath("$.totalElements").value(5),
        jsonPath("$.totalPages").value(3),
        jsonPath("$.content[*].surname").value(contains("Petrova", "Petrov")));
  }

  private void createSampleUsers() throws Exception {
    createUser("Ivan", "Petrov", "ivan.petrov@example.com");
    createUser("Anna", "Petrova", "anna.petrova@example.com");
    createUser("Oleg", "Ivanov", "oleg.ivanov@example.com");
    createUser("Maria", "Smirnova", "maria.smirnova@example.com");
    createUser("Ivan", "Sidorov", "ivan.sidorov@example.com");
  }

  /** The test clock time, as the JSON responses show it. */
  private static String hoursAfterStart(int hours) {
    return START.plus(Duration.ofHours(hours)).toString();
  }
}
