package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tests of the user cache against a real Redis: what exactly is stored, that a cached read
 * stays away from the database, and that every change of a user or of one of its cards removes
 * the entry of that user, and only that one.
 *
 * <p>The assertions on Redis follow the request at once, without waiting. The cache writes and
 * evictions are immediate by configuration, so a test that had to poll would be hiding a
 * regression.
 */
class UserCacheIntegrationTest extends AbstractIntegrationTest {

  private static final String CARD_NUMBER = cardNumber(1);

  @Test
  void cachedEntryHasTheExpectedKeyTtlAndJsonShape() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    createCard(userId, CARD_NUMBER);

    getUser(userId).andExpect(status().isOk());

    String key = userCacheKey(userId);
    String json = redisTemplate.opsForValue().get(key);
    long ttl = Objects.requireNonNull(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS));
    assertNotNull(json);
    String email = JsonPath.read(json, "$.email");
    String birthDate = JsonPath.read(json, "$.birthDate");
    String createdAt = JsonPath.read(json, "$.createdAt");
    String maskedNumber = JsonPath.read(json, "$.cards[0].maskedNumber");
    assertAll(
      () -> assertEquals(Set.of(key), cacheKeys()),
      // The entry has just been written, so it has almost the full time to live of 10 minutes.
      () -> assertTrue(ttl > Duration.ofMinutes(9).toMillis(), "TTL is too short"),
      () -> assertTrue(ttl <= Duration.ofMinutes(10).toMillis(), "TTL is too long"),
      () -> assertEquals("ivan@example.com", email),
      () -> assertEquals("1990-05-17", birthDate),
      () -> assertEquals(START.toString(), createdAt),
      () -> assertEquals("************0001", maskedNumber),
      () -> assertFalse(json.contains(CARD_NUMBER), "the full card number reached Redis"),
      () -> assertFalse(json.contains("@class"), "the value must be plain typed JSON"));
  }

  @Test
  void secondReadIsServedFromRedisWithoutTouchingTheDatabase() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    createCard(userId, CARD_NUMBER);
    Statistics statistics = hibernateStatistics();

    statistics.clear();
    getUser(userId).andExpect(status().isOk());
    long missStatements = statistics.getPrepareStatementCount();
    long missSessions = statistics.getSessionOpenCount();

    statistics.clear();
    getUser(userId)
      .andExpectAll(status().isOk(), jsonPath("$.cards", hasSize(1)));
    long hitStatements = statistics.getPrepareStatementCount();
    long hitSessions = statistics.getSessionOpenCount();

    assertAll(
      // The first read must reach the database, otherwise the zeros below prove nothing.
      () -> assertTrue(missStatements > 0, "the first read did not reach the database"),
      () -> assertTrue(missSessions > 0, "the first read did not open a session"),
      () -> assertEquals(0, hitStatements, "a cached read ran SQL"),
      // No session means no transaction: the cache advice wraps the transaction advice.
      () -> assertEquals(0, hitSessions, "a cached read opened a session"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"update user", "deactivate user", "activate user", "delete user",
    "create card", "update card", "deactivate card", "activate card", "delete card"})
  void everyChangeEvictsTheEntryOfTheOwnerOnly(String change) throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    long ivanCard = createCard(ivan, cardNumber(1));
    createCard(anna, cardNumber(2));
    getUser(ivan).andExpect(status().isOk());
    getUser(anna).andExpect(status().isOk());
    assertEquals(Set.of(userCacheKey(ivan), userCacheKey(anna)), cacheKeys());

    perform(change, ivan, ivanCard).andExpect(status().is2xxSuccessful());

    assertEquals(Set.of(userCacheKey(anna)), cacheKeys());
  }

  @Test
  void readAfterWriteShowsEveryChangeAtOnce() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    long card = createCard(userId, cardNumber(1));
    getUser(userId)
      .andExpectAll(jsonPath("$.surname").value("Petrov"), jsonPath("$.cards", hasSize(1)));

    mockMvc.perform(put("/api/users/{id}", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Sidorov", "ivan@example.com")))
      .andExpect(status().isOk());
    getUser(userId).andExpect(jsonPath("$.surname").value("Sidorov"));

    createCard(userId, cardNumber(2));
    getUser(userId).andExpect(jsonPath("$.cards", hasSize(2)));

    mockMvc.perform(patch("/api/cards/{id}/deactivate", card)).andExpect(status().isOk());
    getUser(userId)
      .andExpect(jsonPath("$.cards[?(@.id == %d)].active", card).value(contains(false)));

    mockMvc.perform(put("/api/cards/{id}", card)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(1), "NEW HOLDER", "2033-01-31")))
      .andExpect(status().isOk());
    getUser(userId)
      .andExpect(jsonPath("$.cards[?(@.id == %d)].holder", card).value(contains("NEW HOLDER")));

    mockMvc.perform(delete("/api/cards/{id}", card)).andExpect(status().isNoContent());
    getUser(userId).andExpect(jsonPath("$.cards", hasSize(1)));

    mockMvc.perform(patch("/api/users/{id}/deactivate", userId)).andExpect(status().isOk());
    getUser(userId).andExpect(jsonPath("$.active").value(false));
  }

  @Test
  void failedChangesKeepTheCachedEntry() throws Exception {
    long ivan = createUser("Ivan", "Petrov", "ivan@example.com");
    long anna = createUser("Anna", "Smirnova", "anna@example.com");
    long ivanCard = createCard(ivan, cardNumber(1));
    for (int index = 2; index <= 5; index++) {
      createCard(ivan, cardNumber(index));
    }
    createCard(anna, cardNumber(6));
    getUser(ivan).andExpect(status().isOk());
    String cached = redisTemplate.opsForValue().get(userCacheKey(ivan));
    assertNotNull(cached);

    mockMvc.perform(put("/api/users/{id}", ivan)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Petrov", "anna@example.com")))
      .andExpect(status().isConflict());
    mockMvc.perform(put("/api/cards/{id}", ivanCard)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(6), "IVAN PETROV", "2032-12-31")))
      .andExpect(status().isConflict());
    mockMvc.perform(post("/api/users/{userId}/cards", ivan)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(7), "IVAN PETROV", "2032-12-31")))
      .andExpect(status().isConflict());

    assertEquals(cached, redisTemplate.opsForValue().get(userCacheKey(ivan)));
  }

  @Test
  void unknownUserIsNotCached() throws Exception {
    getUser(9999).andExpect(status().isNotFound());

    assertTrue(cacheKeys().isEmpty());
  }

  /** Reads a user through the cached endpoint. */
  private ResultActions getUser(long userId) throws Exception {
    return mockMvc.perform(get("/api/users/{id}", userId));
  }

  /** Performs one of the nine changes that must evict the cache entry of the owner. */
  private ResultActions perform(String change, long userId, long cardId) throws Exception {
    return switch (change) {
      case "update user" -> mockMvc.perform(put("/api/users/{id}", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Ivan", "Sidorov", "ivan@example.com")));
      case "deactivate user" -> mockMvc.perform(patch("/api/users/{id}/deactivate", userId));
      case "activate user" -> mockMvc.perform(patch("/api/users/{id}/activate", userId));
      case "delete user" -> mockMvc.perform(delete("/api/users/{id}", userId));
      case "create card" -> mockMvc.perform(post("/api/users/{userId}/cards", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(9), "IVAN PETROV", "2032-12-31")));
      case "update card" -> mockMvc.perform(put("/api/cards/{id}", cardId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(cardNumber(1), "NEW HOLDER", "2033-01-31")));
      case "deactivate card" -> mockMvc.perform(patch("/api/cards/{id}/deactivate", cardId));
      case "activate card" -> mockMvc.perform(patch("/api/cards/{id}/activate", cardId));
      case "delete card" -> mockMvc.perform(delete("/api/cards/{id}", cardId));
      default -> throw new IllegalArgumentException(String.format("Unknown change: %s", change));
    };
  }
}
