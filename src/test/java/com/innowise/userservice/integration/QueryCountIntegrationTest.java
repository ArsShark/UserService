package com.innowise.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

/**
 * Guards against the N+1 select problem: the number of SQL statements of a request must not
 * grow with the number of rows it returns.
 *
 * <p>Each test measures the same request twice, with one row and with five, and compares the
 * counts. For the card lists the five cards belong to five different users. If all five cards
 * belonged to one user, the lazy owner would be loaded once and shared, and an N+1 problem
 * would stay invisible.
 */
class QueryCountIntegrationTest extends AbstractIntegrationTest {

  @Test
  void cardListQueryCountDoesNotGrowWithTheNumberOfOwners() throws Exception {
    createUserWithCard(0);
    long unfilteredOne = statementsOf("/api/cards");
    // The filter joins the owner, which is another code path than the plain list.
    long filteredOne = statementsOf("/api/cards?name=iva");
    for (int index = 1; index < 5; index++) {
      createUserWithCard(index);
    }
    assertEquals(5, countRows("payment_cards"));

    long unfilteredFive = statementsOf("/api/cards");
    long filteredFive = statementsOf("/api/cards?name=iva");

    assertAll(
      () -> assertTrue(unfilteredOne > 0, "the measurement did not see any SQL"),
      () -> assertEquals(unfilteredOne, unfilteredFive, "N+1 in the plain card list"),
      () -> assertEquals(filteredOne, filteredFive, "N+1 in the filtered card list"));
  }

  @Test
  void userReadQueryCountDoesNotGrowWithTheNumberOfCards() throws Exception {
    long light = createUser("Ivan", "Light", "light@example.com");
    createCard(light, cardNumber(1));
    long heavy = createUser("Ivan", "Heavy", "heavy@example.com");
    for (int index = 11; index <= 15; index++) {
      createCard(heavy, cardNumber(index));
    }

    // Both users have never been read, so both reads are cache misses that reach the database.
    long withOneCard = statementsOf("/api/users/{id}", light);
    long withFiveCards = statementsOf("/api/users/{id}", heavy);

    assertAll(
      () -> assertTrue(withOneCard > 0, "the measurement did not see any SQL"),
      () -> assertEquals(withOneCard, withFiveCards, "N+1 on the cards of a user"));
  }

  @Test
  void cardsOfAUserQueryCountDoesNotGrowWithTheNumberOfCards() throws Exception {
    long light = createUser("Ivan", "Light", "light@example.com");
    createCard(light, cardNumber(1));
    long heavy = createUser("Ivan", "Heavy", "heavy@example.com");
    for (int index = 11; index <= 15; index++) {
      createCard(heavy, cardNumber(index));
    }

    long withOneCard = statementsOf("/api/users/{id}/cards", light);
    long withFiveCards = statementsOf("/api/users/{id}/cards", heavy);

    assertAll(
      () -> assertTrue(withOneCard > 0, "the measurement did not see any SQL"),
      () -> assertEquals(withOneCard, withFiveCards, "N+1 in the cards of a user"));
  }

  @Test
  void userListQueryCountDoesNotGrowWithTheNumberOfUsers() throws Exception {
    createUserWithCard(0);
    long withOneUser = statementsOf("/api/users");
    for (int index = 1; index < 5; index++) {
      createUserWithCard(index);
    }

    long withFiveUsers = statementsOf("/api/users");

    assertAll(
      () -> assertTrue(withOneUser > 0, "the measurement did not see any SQL"),
      () -> assertEquals(withOneUser, withFiveUsers, "N+1 in the user list"));
  }

  /** Creates a user with its own name and email and one card. */
  private void createUserWithCard(int index) throws Exception {
    long userId = createUser("Ivan", String.format("Petrov%d", index),
      String.format("user%d@example.com", index));
    createCard(userId, cardNumber(index));
  }

  /** Performs a GET that must succeed and returns the number of SQL statements it ran. */
  private long statementsOf(String url, Object... uriVariables) throws Exception {
    Statistics statistics = hibernateStatistics();
    statistics.clear();
    mockMvc.perform(get(url, uriVariables)).andExpect(status().isOk());
    return statistics.getPrepareStatementCount();
  }
}
