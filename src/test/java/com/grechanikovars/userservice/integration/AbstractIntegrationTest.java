package com.grechanikovars.userservice.integration;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grechanikovars.userservice.RedisTestcontainersConfiguration;
import com.grechanikovars.userservice.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.hamcrest.Matcher;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Base class of the integration tests: the whole application on real PostgreSQL and Redis
 * containers, driven through MockMvc.
 *
 * <p>All subclasses share one Spring context and with it one pair of containers, because the
 * containers are beans of the context and a second context would start a second pair. So
 * subclasses must not change the context: no {@code @MockitoBean}, {@code @TestPropertySource},
 * {@code @DirtiesContext} or extra {@code @Import}. Properties that every test needs belong
 * to this class.
 *
 * <p>The tests are not transactional on purpose. A test transaction would hide the commit, and
 * with it the cache eviction after the commit, the row locks and the concurrent requests that
 * the integration tests are written to check. Instead, {@link #resetState()} empties the
 * database and Redis and resets the clock before every test.
 *
 * <p>Hibernate statistics are switched on for the whole context, so tests can count the
 * statements and sessions of a request and prove that a cached read never reaches the database.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class,
  TestClockConfiguration.class})
abstract class AbstractIntegrationTest {

  /** The instant the test clock shows at the start of every test. */
  protected static final Instant START = Instant.parse("2026-10-16T10:00:00Z");

  private static final String CACHE_KEY_PATTERN = "user-service:*";

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @Autowired
  protected StringRedisTemplate redisTemplate;

  @Autowired
  protected MutableClock clock;

  @Autowired
  protected EntityManagerFactory entityManagerFactory;

  /**
   * Brings the shared state to the initial one: both tables are emptied and the identity
   * counters restart, so the first user of every test has the id 1; Redis is flushed; the clock
   * shows {@link #START}.
   */
  @BeforeEach
  protected void resetState() {
    // Only the tables of the application, not the tables of Liquibase.
    jdbcTemplate.execute("TRUNCATE TABLE payment_cards, users RESTART IDENTITY CASCADE");
    redisTemplate.execute((RedisCallback<Void>) connection -> {
      connection.serverCommands().flushAll();
      return null;
    });
    clock.set(START);
  }

  /**
   * Creates a user through the REST API and checks that the answer is 201.
   *
   * @return the id of the created user
   */
  protected long createUser(String name, String surname, String email) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson(name, surname, email)))
      .andExpect(status().isCreated())
      .andReturn();
    return idOf(result);
  }

  /**
   * Creates a card through the REST API and checks that the answer is 201.
   *
   * @return the id of the created card
   */
  protected long createCard(long userId, String number) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/users/{userId}/cards", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(cardJson(number, "IVAN PETROV", "2032-12-31")))
      .andExpect(status().isCreated())
      .andReturn();
    return idOf(result);
  }

  /** JSON body of a user request. */
  protected static String userJson(String name, String surname, String email) {
    return String.format("""
      {"name": "%s", "surname": "%s", "birthDate": "1990-05-17", "email": "%s"}
      """, name, surname, email);
  }

  /** JSON body of a card request. */
  protected static String cardJson(String number, String holder, String expirationDate) {
    return String.format("""
      {"number": "%s", "holder": "%s", "expirationDate": "%s"}
      """, number, holder, expirationDate);
  }

  /** A valid 16-digit card number that is different for every index. */
  protected static String cardNumber(int index) {
    return String.format("41111111111%05d", index);
  }

  /** Reads the {@code id} field of a JSON response. */
  protected static long idOf(MvcResult result) throws Exception {
    Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    return id.longValue();
  }

  /** Counts the rows of a table of the application. */
  protected long countRows(String table) {
    Long count = jdbcTemplate.queryForObject(
      String.format("SELECT count(*) FROM %s", table), Long.class);
    return Objects.requireNonNull(count);
  }

  /** The Redis key of a cached user, as the cache configuration builds it. */
  protected static String userCacheKey(long userId) {
    return String.format("user-service:users::%d", userId);
  }

  /** All keys of the application in Redis. */
  protected Set<String> cacheKeys() {
    return Objects.requireNonNullElse(redisTemplate.keys(CACHE_KEY_PATTERN), Set.of());
  }

  /** The Hibernate statistics of the shared context: statements, sessions and so on. */
  protected Statistics hibernateStatistics() {
    return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  /** Matches an error response in the RFC 9457 format, {@code application/problem+json}. */
  protected static ResultMatcher problemJson() {
    return content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }

  /** Matches a JSON array of ids that holds exactly the given ids in the given order. */
  protected static Matcher<Iterable<? extends Integer>> idsInOrder(long... ids) {
    Integer[] expected = Arrays.stream(ids).mapToObj(id -> (int) id).toArray(Integer[]::new);
    return contains(expected);
  }
}
