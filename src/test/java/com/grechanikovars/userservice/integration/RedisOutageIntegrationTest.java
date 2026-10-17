package com.grechanikovars.userservice.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grechanikovars.userservice.config.FailOpenCacheErrorHandler;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;

/**
 * Tests of the fail-open behavior against a real Redis that stops answering.
 *
 * <p>The Redis container is paused, not stopped: a paused container keeps its port and its open
 * connections but answers nothing, which is exactly the case the command timeout of 500 ms is
 * for. After a stop and a start the port would change, and the application would never find
 * Redis again.
 *
 * <p>A paused Redis keeps the commands that were sent to it and runs them after it is resumed.
 * So a timed-out write or eviction is not lost here, unlike after a real crash; this test proves
 * that requests survive and are logged, not the loss of an eviction. That limit is accepted and
 * bounded by the TTL.
 */
class RedisOutageIntegrationTest extends AbstractIntegrationTest {

  private static final Duration SLOW_BUT_ALIVE = Duration.ofSeconds(5);

  @Autowired
  @Qualifier("redisContainer")
  @SuppressWarnings("rawtypes")
  private GenericContainer redisContainer;

  /** Makes sure that a failed test never leaves the shared Redis paused for the next tests. */
  @AfterEach
  void resumeRedis() {
    if (isPaused()) {
      redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
    }
  }

  @Test
  void requestsSucceedWhileRedisIsPaused() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    createCard(userId, cardNumber(1));
    getUser(userId).andExpect(status().isOk());
    assertEquals(Set.of(userCacheKey(userId)), cacheKeys());

    pauseRedis();
    Duration read;
    Duration update;
    try {
      // A cache miss in disguise: the read times out, the database answers, the write times out.
      long started = System.nanoTime();
      getUser(userId)
        .andExpectAll(status().isOk(), jsonPath("$.surname").value("Petrov"));
      read = Duration.ofNanos(System.nanoTime() - started);

      started = System.nanoTime();
      mockMvc.perform(put("/api/users/{id}", userId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(userJson("Ivan", "Sidorov", "ivan@example.com")))
        .andExpect(status().isOk());
      update = Duration.ofNanos(System.nanoTime() - started);

      mockMvc.perform(post("/api/users/{userId}/cards", userId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(cardJson(cardNumber(2), "IVAN PETROV", "2032-12-31")))
        .andExpect(status().isCreated());
      // The old entry cannot be evicted, but it cannot be read either: the answer is fresh.
      getUser(userId)
        .andExpectAll(
          status().isOk(),
          jsonPath("$.surname").value("Sidorov"),
          jsonPath("$.cards", hasSize(2)));
    } finally {
      resumeRedis();
    }

    assertAll(
      () -> assertTrue(read.compareTo(SLOW_BUT_ALIVE) < 0, String.format("a read took %s", read)),
      () -> assertTrue(update.compareTo(SLOW_BUT_ALIVE) < 0,
        String.format("an update took %s", update)),
      () -> assertEquals("Sidorov", jdbcTemplate.queryForObject(
        "SELECT surname FROM users WHERE id = ?", String.class, userId)),
      () -> assertEquals(2, countRows("payment_cards")));
  }

  @Test
  void cacheFailuresAreLoggedAsWarningsWithoutPersonalData() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan.petrov@example.com");
    LogCapture capture = LogCapture.attach();
    try {
      pauseRedis();
      try {
        getUser(userId).andExpect(status().isOk());
        mockMvc.perform(put("/api/users/{id}", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(userJson("Ivan", "Sidorov", "ivan.petrov@example.com")))
          .andExpect(status().isOk());
      } finally {
        resumeRedis();
      }
    } finally {
      capture.detach();
    }

    List<String> messages = capture.messages();
    assertAll(
      () -> assertTrue(containsPart(messages, "Cache 'users' read failed for key"), "read"),
      () -> assertTrue(containsPart(messages, "Cache 'users' write failed for key"), "write"),
      () -> assertTrue(containsPart(messages, "Cache 'users' eviction failed for key"), "evict"),
      () -> assertTrue(messages.stream().allMatch(message -> message.startsWith("WARN ")),
        String.format("cache failures must be logged as WARN: %s", messages)),
      () -> assertTrue(messages.stream().noneMatch(message -> message.contains("example.com")
        || message.contains("Ivan") || message.contains("Petrov")),
        String.format("a log line contains personal data: %s", messages)));
  }

  @Test
  void cacheWorksAgainAfterRedisIsResumed() throws Exception {
    long userId = createUser("Ivan", "Petrov", "ivan@example.com");
    createCard(userId, cardNumber(1));
    pauseRedis();
    try {
      getUser(userId).andExpect(status().isOk());
    } finally {
      resumeRedis();
    }

    awaitCacheRecovery(userId);

    Statistics statistics = hibernateStatistics();
    statistics.clear();
    getUser(userId).andExpect(status().isOk());
    assertAll(
      () -> assertEquals(Set.of(userCacheKey(userId)), cacheKeys()),
      () -> assertEquals(0, statistics.getPrepareStatementCount(),
        "after the recovery the read must come from the cache"));
  }

  /**
   * Flushes Redis and reads the user until the cache entry appears. A resumed Redis first runs
   * the commands that piled up while it was paused, and those can delete or write an entry
   * after our flush, so a single check could be fooled.
   */
  private void awaitCacheRecovery(long userId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        flushRedis();
        getUser(userId).andExpect(status().isOk());
        if (cacheKeys().contains(userCacheKey(userId))) {
          return;
        }
      } catch (DataAccessException stillRecovering) {
        // Redis may need a moment to answer again.
      }
      Thread.sleep(200);
    }
    fail("The cache did not recover within 30 seconds after Redis was resumed");
  }

  private ResultActions getUser(long userId) throws Exception {
    return mockMvc.perform(get("/api/users/{id}", userId));
  }

  private void pauseRedis() {
    redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
  }

  private boolean isPaused() {
    return Boolean.TRUE.equals(redisContainer.getDockerClient()
      .inspectContainerCmd(redisContainer.getContainerId())
      .exec()
      .getState()
      .getPaused());
  }

  private static boolean containsPart(List<String> messages, String part) {
    return messages.stream().anyMatch(message -> message.contains(part));
  }

  /**
   * Collects the log lines of {@link FailOpenCacheErrorHandler} while it is attached. A plain
   * output capture would be unreliable here: the Log4j2 console appender keeps the stream that
   * was current when the logging started.
   */
  private static final class LogCapture extends AbstractAppender {

    private static final String NAME = "cache-failure-capture";

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final LoggerContext context;
    private final LoggerConfig loggerConfig;

    private LogCapture(LoggerContext context, LoggerConfig loggerConfig) {
      super(NAME, null, null, true, new Property[0]);
      this.context = context;
      this.loggerConfig = loggerConfig;
    }

    static LogCapture attach() {
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      LoggerConfig loggerConfig =
        context.getConfiguration().getLoggerConfig(FailOpenCacheErrorHandler.class.getName());
      LogCapture capture = new LogCapture(context, loggerConfig);
      capture.start();
      loggerConfig.addAppender(capture, Level.WARN, null);
      context.updateLoggers();
      return capture;
    }

    void detach() {
      loggerConfig.removeAppender(NAME);
      stop();
      context.updateLoggers();
    }

    List<String> messages() {
      return List.copyOf(lines);
    }

    @Override
    public void append(LogEvent event) {
      if (FailOpenCacheErrorHandler.class.getName().equals(event.getLoggerName())) {
        lines.add(String.format("%s %s", event.getLevel(),
          event.getMessage().getFormattedMessage()));
      }
    }
  }
}
