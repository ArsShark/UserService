package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Tests who may touch whose data, end to end: real tokens, the real database, the real cache.
 *
 * <p>Anna and Ivan are two ordinary users. Anna has one card. Every test uses the token of one of
 * them and checks both the answer and what is left in the database, because a refused request
 * must change nothing.
 */
class OwnershipIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCESS_DENIED = "Access is denied";

  private long annaId;
  private long ivanId;
  private long annaCardId;
  private String annaToken;
  private String ivanToken;

  @BeforeEach
  void createTwoUsers() throws Exception {
    annaId = createUser("Anna", "Petrova", "anna@example.com");
    ivanId = createUser("Ivan", "Petrov", "ivan@example.com");
    annaCardId = createCard(annaId, cardNumber(1));
    annaToken = TestTokens.access().subject("101").userId(annaId)
        .email("anna@example.com").build();
    ivanToken = TestTokens.access().subject("102").userId(ivanId)
        .email("ivan@example.com").build();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,
      String token) {
    return request.header(HttpHeaders.AUTHORIZATION, bearer(token));
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
      String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request);
  }

  private String stored(String column, long userId) {
    return jdbcTemplate.queryForObject(
        String.format("SELECT %s FROM users WHERE id = ?", column), String.class, userId);
  }

  private Boolean storedActive(String table, long id) {
    return jdbcTemplate.queryForObject(
        String.format("SELECT active FROM %s WHERE id = ?", table), Boolean.class, id);
  }

  @Test
  void aUserReadsHisOwnProfileTogetherWithHisCards() throws Exception {
    perform(as(get("/api/users/{id}", annaId), annaToken)).andExpectAll(
        status().isOk(),
        jsonPath("$.email").value("anna@example.com"),
        jsonPath("$.cards", hasSize(1)));
  }

  @Test
  void aUserCannotReadTheProfileOfSomebodyElse() throws Exception {
    perform(as(get("/api/users/{id}", annaId), ivanToken)).andExpectAll(
        status().isForbidden(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.detail").value(ACCESS_DENIED),
        content().string(not(containsString("Anna"))),
        content().string(not(containsString("anna@example.com"))));
  }

  /**
   * The cache sits around the transaction of the service; the check sits in front of both. So an
   * entry that is already in Redis must not be given to a caller who does not own it.
   */
  @Test
  void aCachedProfileIsNotGivenToSomebodyElse() throws Exception {
    perform(as(get("/api/users/{id}", annaId), annaToken)).andExpect(status().isOk());
    assertTrue(cacheKeys().contains(userCacheKey(annaId)));

    perform(as(get("/api/users/{id}", annaId), ivanToken)).andExpect(status().isForbidden());

    assertTrue(cacheKeys().contains(userCacheKey(annaId)));
  }

  @Test
  void aUserReplacesHisOwnDataButNotHisEmail() throws Exception {
    perform(json(as(put("/api/users/{id}", annaId), annaToken),
        userJson("Anna-Maria", "Petrova", "anna@example.com"))).andExpectAll(
        status().isOk(),
        jsonPath("$.name").value("Anna-Maria"));
    perform(json(as(put("/api/users/{id}", annaId), annaToken),
        userJson("Anna", "Petrova", "ANNA@Example.com"))).andExpect(status().isOk());

    perform(json(as(put("/api/users/{id}", annaId), annaToken),
        userJson("Anna", "Petrova", "new@example.com"))).andExpectAll(
        status().isForbidden(),
        jsonPath("$.detail").value(ACCESS_DENIED));

    assertAll(
        () -> assertEquals("anna@example.com", stored("email", annaId)),
        () -> assertEquals("Anna", stored("name", annaId)));
  }

  @Test
  void aUserCannotReplaceTheProfileOfSomebodyElse() throws Exception {
    perform(json(as(put("/api/users/{id}", annaId), ivanToken),
        userJson("Hacked", "Hacked", "anna@example.com"))).andExpect(status().isForbidden());

    assertEquals("Anna", stored("name", annaId));
  }

  /** An admin may change an email; the login in the Authentication Service then stays old. */
  @Test
  void anAdminMayChangeTheEmailOfAUser() throws Exception {
    perform(json(put("/api/users/{id}", annaId),
        userJson("Anna", "Petrova", "new@example.com"))).andExpect(status().isOk());

    assertEquals("new@example.com", stored("email", annaId));
  }

  @Test
  void aUserCannotUseTheOperationsOfAnAdmin() throws Exception {
    perform(as(get("/api/users"), annaToken)).andExpect(status().isForbidden());
    perform(as(get("/api/cards"), annaToken)).andExpect(status().isForbidden());
    perform(as(userStatus(annaId, false), annaToken))
        .andExpect(status().isForbidden());
    perform(as(userStatus(annaId, true), annaToken))
        .andExpect(status().isForbidden());
    perform(as(delete("/api/users/{id}", annaId), annaToken)).andExpect(status().isForbidden());

    assertAll(
        () -> assertEquals(2, countRows("users")),
        () -> assertTrue(storedActive("users", annaId)));
  }

  @Test
  void aUserManagesHisOwnCards() throws Exception {
    long created = idOf(perform(json(as(post("/api/users/{id}/cards", annaId), annaToken),
        cardJson(cardNumber(2), "ANNA PETROVA", "2032-12-31")))
        .andExpect(status().isCreated()).andReturn());

    perform(as(get("/api/users/{id}/cards", annaId), annaToken)).andExpectAll(
        status().isOk(),
        jsonPath("$", hasSize(2)));
    perform(as(get("/api/cards/{id}", created), annaToken)).andExpect(status().isOk());
    perform(json(as(put("/api/cards/{id}", created), annaToken),
        cardJson(cardNumber(3), "ANNA P PETROVA", "2033-12-31"))).andExpectAll(
        status().isOk(),
        jsonPath("$.holder").value("ANNA P PETROVA"));
    perform(as(patch("/api/cards/{id}/deactivate", created), annaToken)).andExpectAll(
        status().isOk(),
        jsonPath("$.active").value(false));
    perform(as(delete("/api/cards/{id}", created), annaToken))
        .andExpect(status().isNoContent());

    assertEquals(1, countRows("payment_cards"));
  }

  /** A user may block his card, but only an admin may lift a block. */
  @Test
  void aUserCannotActivateACard() throws Exception {
    perform(as(patch("/api/cards/{id}/deactivate", annaCardId), annaToken))
        .andExpect(status().isOk());

    perform(as(patch("/api/cards/{id}/activate", annaCardId), annaToken)).andExpectAll(
        status().isForbidden(),
        jsonPath("$.detail").value(ACCESS_DENIED));
    perform(patch("/api/cards/{id}/activate", annaCardId)).andExpect(status().isOk());
  }

  @Test
  void aUserCannotTouchTheCardsOfSomebodyElse() throws Exception {
    perform(json(as(post("/api/users/{id}/cards", annaId), ivanToken),
        cardJson(cardNumber(2), "IVAN PETROV", "2032-12-31"))).andExpect(status().isForbidden());
    perform(as(get("/api/users/{id}/cards", annaId), ivanToken))
        .andExpect(status().isForbidden());
    perform(as(get("/api/cards/{id}", annaCardId), ivanToken)).andExpect(status().isNotFound());
    perform(json(as(put("/api/cards/{id}", annaCardId), ivanToken),
        cardJson(cardNumber(9), "IVAN PETROV", "2032-12-31"))).andExpect(status().isNotFound());
    perform(as(patch("/api/cards/{id}/deactivate", annaCardId), ivanToken))
        .andExpect(status().isNotFound());
    perform(as(delete("/api/cards/{id}", annaCardId), ivanToken))
        .andExpect(status().isNotFound());

    assertAll(
        () -> assertEquals(1, countRows("payment_cards")),
        () -> assertTrue(storedActive("payment_cards", annaCardId)),
        () -> assertEquals(cardNumber(1), jdbcTemplate.queryForObject(
            "SELECT number FROM payment_cards WHERE id = ?", String.class, annaCardId)));
  }

  /** The caller cannot find out which card ids are taken: the same answer in both cases. */
  @Test
  void aForeignCardLooksLikeAMissingCard() throws Exception {
    String foreign = perform(as(get("/api/cards/{id}", annaCardId), ivanToken))
        .andReturn().getResponse().getContentAsString();
    String missing = perform(as(get("/api/cards/{id}", annaCardId + 1000), ivanToken))
        .andReturn().getResponse().getContentAsString();

    assertEquals(foreign.replaceAll("\\d+", "N"), missing.replaceAll("\\d+", "N"));
  }

  /** The profile claim is absent for an admin without a profile; as a user it owns nothing. */
  @Test
  void aTokenWithoutAProfileOwnsNothing() throws Exception {
    String token = TestTokens.access().userId(null).build();

    perform(as(get("/api/users/{id}", annaId), token)).andExpect(status().isForbidden());
    perform(as(get("/api/users/{id}/cards", annaId), token)).andExpect(status().isForbidden());
    perform(as(get("/api/cards/{id}", annaCardId), token)).andExpect(status().isForbidden());
  }

  @Test
  void anAdminReachesEverybodysData() throws Exception {
    perform(get("/api/users/{id}", annaId)).andExpect(status().isOk());
    perform(get("/api/users/{id}/cards", ivanId)).andExpect(status().isOk());
    perform(get("/api/cards/{id}", annaCardId)).andExpect(status().isOk());
    perform(userStatus(annaId, false)).andExpect(status().isOk());
  }
}
