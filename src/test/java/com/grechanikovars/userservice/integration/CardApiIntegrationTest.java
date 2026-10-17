package com.grechanikovars.userservice.integration;

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
 * End-to-end scenarios of the card API, from the controller to PostgreSQL.
 *
 * <p>Like {@link UserApiIntegrationTest}, they cover only what needs all layers at once: the
 * limit of five cards under a real row lock, the unique card number enforced together with the
 * database, the timestamps of bulk updates and the filters that join the owner.
 */
class CardApiIntegrationTest extends AbstractIntegrationTest {

  private static final String NUMBER = cardNumber(1);

  @Test
  void cardLifecycleKeepsAuditingTimestampsAndMasksTheNumber() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");

    MvcResult created = mockMvc.perform(post("/api/users/{userId}/cards", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(NUMBER, "IVAN PETROV", "2032-12-31")))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.userId").value(userId),
        jsonPath("$.maskedNumber").value("************0001"),
        jsonPath("$.number").doesNotExist(),
        jsonPath("$.active").value(true),
        jsonPath("$.createdAt").value(hoursAfterStart(0)))
      .andReturn();
    long id = idOf(created);
    assertAll(
      () -> assertEquals(String.format("http://localhost/api/cards/%d", id),
        created.getResponse().getHeader(HttpHeaders.LOCATION)),
      // The full number is stored; only the responses and the cache hide it.
      () -> assertEquals(NUMBER, storedNumber(id)));

    clock.advance(Duration.ofHours(1));
    mockMvc.perform(put("/api/cards/{id}", id)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(2), "ANNA PETROVA", "2033-01-31")))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.userId").value(userId),
        jsonPath("$.maskedNumber").value("************0002"),
        jsonPath("$.holder").value("ANNA PETROVA"),
        jsonPath("$.createdAt").value(hoursAfterStart(0)),
        jsonPath("$.updatedAt").value(hoursAfterStart(1)));

    // Deactivation is a native bulk update that bypasses JPA Auditing: the service sets updatedAt.
    clock.advance(Duration.ofHours(1));
    mockMvc.perform(patch("/api/cards/{id}/deactivate", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(false),
        jsonPath("$.createdAt").value(hoursAfterStart(0)),
        jsonPath("$.updatedAt").value(hoursAfterStart(2)));

    clock.advance(Duration.ofHours(1));
    mockMvc.perform(patch("/api/cards/{id}/activate", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(true),
        jsonPath("$.updatedAt").value(hoursAfterStart(3)));

    mockMvc.perform(get("/api/cards/{id}", id))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.holder").value("ANNA PETROVA"),
        jsonPath("$.active").value(true),
        jsonPath("$.updatedAt").value(hoursAfterStart(3)));

    mockMvc.perform(delete("/api/cards/{id}", id)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/cards/{id}", id))
      .andExpectAll(status().isNotFound(), problemJson());
    assertEquals(0, countRows("payment_cards"));
  }

  @Test
  void userCanHaveAtMostFiveCards() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    long firstCard = createCard(ivan, cardNumber(1));
    for (int index = 2; index <= 5; index++) {
      createCard(ivan, cardNumber(index));
    }

    mockMvc.perform(post("/api/users/{userId}/cards", ivan)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(6), "IVAN PETROV", "2032-12-31")))
      .andExpectAll(
        status().isConflict(),
        problemJson(),
        jsonPath("$.detail").value(String.format(
          "User with id %d already has the maximum of 5 payment cards", ivan)));
    assertEquals(5, cardsOf(ivan));

    // The limit is per user.
    createCard(anna, cardNumber(7));
    assertEquals(1, cardsOf(anna));

    // A freed slot can be used again.
    mockMvc.perform(delete("/api/cards/{id}", firstCard)).andExpect(status().isNoContent());
    createCard(ivan, cardNumber(6));
    assertEquals(5, cardsOf(ivan));
  }

  @Test
  void cardNumbersAreUniqueAcrossAllUsers() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    createCard(ivan, cardNumber(1));
    long annaCard = createCard(anna, cardNumber(2));

    // Another user cannot register a number that is already taken.
    mockMvc.perform(post("/api/users/{userId}/cards", anna)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(1), "ANNA SMIRNOVA", "2032-12-31")))
      .andExpectAll(status().isConflict(), problemJson());
    // A card cannot take the number of another card either.
    mockMvc.perform(put("/api/cards/{id}", annaCard)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(1), "ANNA SMIRNOVA", "2032-12-31")))
      .andExpectAll(status().isConflict(), problemJson());
    // Keeping its own number while changing the holder is not a conflict.
    mockMvc.perform(put("/api/cards/{id}", annaCard)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(2), "ANNA SMIRNOVA", "2032-12-31")))
      .andExpectAll(status().isOk(), jsonPath("$.holder").value("ANNA SMIRNOVA"));

    assertAll(
      () -> assertEquals(2, countRows("payment_cards")),
      () -> assertEquals(cardNumber(2), storedNumber(annaCard)));
  }

  @Test
  void cardsOfAUserAreListedAndAnUnknownUserIsRejected() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    createCard(ivan, cardNumber(1));
    createCard(ivan, cardNumber(2));

    mockMvc.perform(get("/api/users/{userId}/cards", ivan))
      .andExpectAll(
        status().isOk(),
        jsonPath("$").value(hasSize(2)),
        jsonPath("$[?(@.userId != %d)]", ivan).isEmpty());
    // A user without cards is an empty list, not an error.
    mockMvc.perform(get("/api/users/{userId}/cards", anna))
      .andExpectAll(status().isOk(), jsonPath("$").isEmpty());
    mockMvc.perform(get("/api/users/{userId}/cards", 9999))
      .andExpectAll(status().isNotFound(), problemJson());
  }

  @Test
  void cardsListFiltersByOwnerPagesAndSortsByCreationTime() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    long first = createCard(ivan, cardNumber(1));
    clock.advance(Duration.ofMinutes(1));
    long second = createCard(anna, cardNumber(2));
    clock.advance(Duration.ofMinutes(1));
    long third = createCard(ivan, cardNumber(3));

    mockMvc.perform(get("/api/cards").param("name", "IVAN"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(2),
        jsonPath("$.content[*].id").value(idsInOrder(first, third)));
    mockMvc.perform(get("/api/cards").param("surname", "smir"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(1),
        jsonPath("$.content[*].id").value(idsInOrder(second)));
    mockMvc.perform(get("/api/cards").param("name", "zzz"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(0),
        jsonPath("$.content").isEmpty());

    // Newest first: the creation times differ because the test clock moved between the cards.
    mockMvc.perform(get("/api/cards").param("size", "2").param("sort", "createdAt,desc"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.page").value(0),
        jsonPath("$.totalElements").value(3),
        jsonPath("$.totalPages").value(2),
        jsonPath("$.content[*].id").value(idsInOrder(third, second)));
    mockMvc.perform(get("/api/cards")
        .param("size", "2")
        .param("page", "1")
        .param("sort", "createdAt,desc"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.page").value(1),
        jsonPath("$.content[*].id").value(idsInOrder(first)));
  }

  private String storedNumber(long cardId) {
    return jdbcTemplate.queryForObject(
      "SELECT number FROM payment_cards WHERE id = ?", String.class, cardId);
  }

  private long cardsOf(long userId) {
    Long count = jdbcTemplate.queryForObject(
      "SELECT count(*) FROM payment_cards WHERE user_id = ?", Long.class, userId);
    return count == null ? 0 : count;
  }

  /** The test clock time, as the JSON responses show it. */
  private static String hoursAfterStart(int hours) {
    return START.plus(Duration.ofHours(hours)).toString();
  }
}
