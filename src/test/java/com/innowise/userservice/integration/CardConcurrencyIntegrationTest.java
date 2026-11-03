package com.innowise.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.MediaType;

/**
 * Tests of the card rules under parallel requests: the limit of five cards per user and the
 * unique card number. Neither can be proved by a single request at a time.
 *
 * <p>Every request is a real HTTP-level call on its own thread, and all threads are released
 * at the same moment by a barrier. A race is a matter of luck, so each test is repeated: a lost
 * lock would show up in one of the repetitions with near certainty, while a correct
 * implementation passes every time, because the outcome checked here is the same whichever
 * way the requests interleave.
 */
class CardConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int CONFLICT = 409;

  @RepeatedTest(5)
  void parallelCreatesNeverExceedTheLimitOfFiveCards() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    for (int index = 1; index <= 4; index++) {
      createCard(userId, cardNumber(index));
    }
    // Every request brings a different number, so a conflict can only come from the limit.
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      requests.add(postCard(userId, cardNumber(100 + index)));
    }

    List<Integer> statuses = runTogether(requests);

    assertAll(
      () -> assertEquals(1, Collections.frequency(statuses, CREATED),
        "exactly one request must get the last free slot"),
      () -> assertEquals(7, Collections.frequency(statuses, CONFLICT),
        "all other requests must be rejected with 409"),
      () -> assertEquals(5, cardsOf(userId)));
  }

  @RepeatedTest(5)
  void parallelActivationsNeverExceedTheLimitOfFiveActiveCards() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    List<Long> cards = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      cards.add(createCard(userId, cardNumber(index)));
    }
    // Three deactivated cards make room for two new active ones: four active, three inactive.
    for (int index = 2; index < 5; index++) {
      deactivate(cards.get(index));
    }
    createCard(userId, cardNumber(6));
    createCard(userId, cardNumber(7));
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int index = 2; index < 5; index++) {
      requests.add(activateCard(cards.get(index)));
    }

    List<Integer> statuses = runTogether(requests);

    assertAll(
      () -> assertEquals(1, Collections.frequency(statuses, OK),
        "exactly one activation must get the last free slot"),
      () -> assertEquals(2, Collections.frequency(statuses, CONFLICT),
        "the other activations must be rejected with 409"),
      () -> assertEquals(5, activeCardsOf(userId)));
  }

  @RepeatedTest(5)
  void parallelCreatesOfTheSameNumberGiveExactlyOneCard() throws Exception {
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      long userId = createUser("Ivan", String.format("Petrov%d", index),
        String.format("user%d@example.com", index));
      requests.add(postCard(userId, cardNumber(1)));
    }

    List<Integer> statuses = runTogether(requests);

    // A request that loses the race on the UNIQUE constraint must end as 409, never as 500.
    assertAll(
      () -> assertEquals(1, Collections.frequency(statuses, CREATED)),
      () -> assertEquals(3, Collections.frequency(statuses, CONFLICT)),
      () -> assertEquals(1, countRows("payment_cards")));
  }

  /** A request that creates a card and returns the HTTP status of the answer. */
  private Callable<Integer> postCard(long userId, String number) {
    return () -> mockMvc.perform(post("/api/users/{userId}/cards", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(number, "IVAN PETROV", "2032-12-31")))
      .andReturn()
      .getResponse()
      .getStatus();
  }

  /** A request that activates a card and returns the HTTP status of the answer. */
  private Callable<Integer> activateCard(long cardId) {
    return () -> mockMvc.perform(patch("/api/cards/{id}/activate", cardId))
      .andReturn()
      .getResponse()
      .getStatus();
  }

  private void deactivate(long cardId) throws Exception {
    int status = mockMvc.perform(patch("/api/cards/{id}/deactivate", cardId))
      .andReturn()
      .getResponse()
      .getStatus();
    assertEquals(OK, status);
  }

  /** Runs the tasks on separate threads, starts them at the same moment and collects results. */
  private List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    CyclicBarrier barrier = new CyclicBarrier(tasks.size());
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> task : tasks) {
        futures.add(executor.submit(() -> {
          barrier.await(30, TimeUnit.SECONDS);
          return task.call();
        }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) {
        statuses.add(future.get(60, TimeUnit.SECONDS));
      }
      return statuses;
    } finally {
      executor.shutdownNow();
    }
  }

  private long activeCardsOf(long userId) {
    Long count = jdbcTemplate.queryForObject(
      "SELECT count(*) FROM payment_cards WHERE user_id = ? AND active", Long.class, userId);
    return count == null ? 0 : count;
  }

  private long cardsOf(long userId) {
    Long count = jdbcTemplate.queryForObject(
      "SELECT count(*) FROM payment_cards WHERE user_id = ?", Long.class, userId);
    return count == null ? 0 : count;
  }
}
