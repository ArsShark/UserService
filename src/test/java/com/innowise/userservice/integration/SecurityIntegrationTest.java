package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.TestKeys;
import com.innowise.userservice.TestTokens;
import com.innowise.userservice.security.InternalKeyAuthenticationFilter;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the security chain of the whole application: real tokens, verified with the key set that
 * a small HTTP server serves, real beans and a real database.
 *
 * <p>The tests of the web slice ({@code SecurityConfigTest}) prove the rules one by one; these
 * prove that the chain is applied to every request, that every kind of bad token gets the very
 * same answer, that time comes from the application clock, and that nothing slips through to the
 * database.
 *
 * <p>{@link #mockMvc} sends an admin token by default. A request that needs another caller sets
 * its own {@code Authorization} header, and a request with no token at all goes through
 * {@code anonymous}, a MockMvc without the default.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String ACCESS_DENIED = "Access is denied";

  @Value("${app.internal.api-key}")
  private String internalKey;

  @Autowired
  private ApplicationContext context;

  @Autowired
  private WebApplicationContext webContext;

  private MockMvc anonymous;

  @BeforeEach
  void buildAnonymousMockMvc() {
    anonymous = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
  }

  /** Every kind of token that must be refused, with a name for the report. */
  static Stream<Arguments> badTokens() {
    String userToken = TestTokens.access().build();
    String adminToken = TestTokens.admin().build();
    String[] user = userToken.split("\\.");
    String[] admin = adminToken.split("\\.");
    String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
        .issuer(TestTokens.ISSUER)
        .subject("2")
        .claim("role", "ADMIN")
        .claim("token_type", "access")
        .expirationTime(Date.from(TestTokens.ISSUED_AT.plus(Duration.ofMinutes(15))))
        .build()).serialize();
    return Stream.of(
        // The token must also have been issued before it expired: JwtClaimsSet refuses otherwise.
        arguments("expired", TestTokens.admin()
            .issuedAt(START.minus(TestTokens.ACCESS_TTL))
            .expiresAt(START.minusSeconds(1)).build()),
        arguments("a refresh token", TestTokens.admin().tokenType("refresh").build()),
        arguments("a token without a type", TestTokens.admin().tokenType(null).build()),
        arguments("another issuer", TestTokens.admin().issuer("somebody-else").build()),
        arguments("no issuer", TestTokens.admin().issuer(null).build()),
        arguments("no expiry", TestTokens.admin().expiresAt(null).build()),
        arguments("signed with another key",
            TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).build()),
        arguments("a key id that the key set does not have",
            TestTokens.admin().keyId("unknown-key").build()),
        arguments("unsigned", unsigned),
        arguments("the payload of an admin under the signature of a user",
            String.format("%s.%s.%s", user[0], admin[1], user[2])),
        arguments("garbage", "not.a.token"));
  }

  private static MockHttpServletRequestBuilder registration(String path) {
    return post(path).contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Anna", "Petrova", "anna@example.com"));
  }

  private static String bodyOf(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * Spring Boot would create an in-memory user with a random password and print the password to
   * the log; the exclusion in the application class must prevent that.
   */
  @Test
  void noDefaultUserDetailsServiceExists() {
    assertEquals(0, context.getBeanNamesForType(UserDetailsService.class).length);
  }

  /** The decoder reads the key set over HTTP, so this is the real way of {@code jwk-set-uri}. */
  @Test
  void anAdminTokenIsAcceptedThroughTheRealKeySet() throws Exception {
    mockMvc.perform(get("/api/users")).andExpect(status().isOk());
  }

  @Test
  void anonymousCallsAreUnauthorizedAndNothingIsWritten() throws Exception {
    anonymous.perform(registration("/api/users")).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
    anonymous.perform(get("/api/users")).andExpect(status().isUnauthorized());
    anonymous.perform(get("/api/cards/1")).andExpect(status().isUnauthorized());

    assertEquals(0, countRows("users"));
  }

  /** Without the chain this would be a 404 (GET) or a 405 (POST, the static resources answer). */
  @Test
  void anonymousCallsToUnknownPathsAreUnauthorizedToo() throws Exception {
    anonymous.perform(get("/unknown")).andExpect(status().isUnauthorized());
    anonymous.perform(post("/unknown")).andExpect(status().isUnauthorized());
    anonymous.perform(get("/error")).andExpect(status().isUnauthorized());
  }

  /**
   * Each case must give the same status and the same body as a request with no token at all, so
   * a caller learns nothing about why the token was refused.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("badTokens")
  void everyKindOfBadTokenGetsTheSameAnswerAsNoToken(String kind, String token)
      throws Exception {
    String expected = bodyOf(anonymous.perform(get("/api/users")).andReturn());

    MvcResult result = anonymous.perform(get("/api/users")
        .header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED),
        content().string(not(containsString(token)))).andReturn();

    assertEquals(expected, bodyOf(result));
  }

  /** A token in the query string would end up in access logs; it must not authenticate. */
  @Test
  void aTokenInTheQueryStringIsIgnored() throws Exception {
    anonymous.perform(get("/api/users").param("access_token", ADMIN_TOKEN))
        .andExpect(status().isUnauthorized());
  }

  /** Time comes from the application clock: the test moves it and the same token expires. */
  @Test
  void aTokenIsAcceptedUntilItsExpiryAndNotAfterIt() throws Exception {
    String token = TestTokens.admin().build();
    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());

    clock.advance(TestTokens.ACCESS_TTL.plusSeconds(1));

    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpectAll(
            status().isUnauthorized(),
            jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  /** A valid token with a role that this service does not know opens nothing: 403, not 401. */
  @ParameterizedTest
  @ValueSource(strings = {"ROOT", "admin", "ADMIN USER", ""})
  void aTokenWithAnUnknownRoleIsForbidden(String role) throws Exception {
    String token = TestTokens.admin().role(role).build();

    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpectAll(
            status().isForbidden(),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.detail").value(ACCESS_DENIED));
  }

  @Test
  void aTokenWithoutARoleIsForbidden() throws Exception {
    String token = TestTokens.admin().role(null).build();

    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isForbidden());
  }

  /** Only an admin and the Gateway create profiles; a user, even a valid one, may not. */
  @Test
  void aUserTokenIsForbiddenOnTheAdminEndpointsAndNothingIsWritten() throws Exception {
    String token = TestTokens.access().build();

    mockMvc.perform(registration("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpectAll(
            status().isForbidden(),
            jsonPath("$.detail").value(ACCESS_DENIED));

    assertEquals(0, countRows("users"));
  }

  @Test
  void theInternalKeyCreatesAProfileAndStartsNoSession() throws Exception {
    MvcResult result = anonymous.perform(registration("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey)).andExpectAll(
        status().isCreated(),
        header().doesNotExist("Location"),
        jsonPath("$.id").isNumber()).andReturn();

    assertAll(
        () -> assertNull(result.getRequest().getSession(false)),
        () -> assertNull(result.getResponse().getHeader("Set-Cookie")),
        () -> assertEquals(1, countRows("users")));
  }

  /** The caller must not learn whether a key was missing or wrong, or how close it was. */
  @Test
  void aMissingAndAWrongKeyGetTheSameAnswerAndNothingIsWritten() throws Exception {
    MvcResult missing = anonymous.perform(registration("/internal/users"))
        .andExpect(status().isUnauthorized()).andReturn();
    MvcResult wrong = anonymous.perform(registration("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, "wrong-key"))
        .andExpect(status().isUnauthorized()).andReturn();

    assertAll(
        () -> assertEquals(bodyOf(missing), bodyOf(wrong)),
        () -> assertTrue(bodyOf(missing).contains(AUTHENTICATION_REQUIRED)),
        () -> assertEquals(0, countRows("users")));
  }

  /** The key has a meaning only under /internal; the public path stays closed to it. */
  @Test
  void theRightKeyOutsideTheInternalPathAuthenticatesNobodyAndWritesNothing() throws Exception {
    anonymous.perform(registration("/api/users")
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey))
        .andExpect(status().isUnauthorized());

    assertEquals(0, countRows("users"));
  }

  /** An admin is authenticated, but the internal role is not his. */
  @Test
  void anAdminTokenIsForbiddenOnTheInternalEndpointsAndNothingIsWritten() throws Exception {
    mockMvc.perform(registration("/internal/users")).andExpectAll(
        status().isForbidden(),
        jsonPath("$.detail").value(ACCESS_DENIED));

    assertEquals(0, countRows("users"));
  }

  @Test
  void theInternalDeleteRemovesTheProfileAndAnswers404TheSecondTime() throws Exception {
    long id = createUser("Anna", "Petrova", "anna@example.com");
    createCard(id, cardNumber(1));

    anonymous.perform(delete("/internal/users/{id}", id)
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey))
        .andExpect(status().isNoContent());
    anonymous.perform(delete("/internal/users/{id}", id)
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey)).andExpectAll(
        status().isNotFound(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertAll(
        () -> assertEquals(0, countRows("users")),
        () -> assertEquals(0, countRows("payment_cards")));
  }

  /** Only two endpoints are opened; with the right key the rest of /internal stays closed. */
  @Test
  void anInternalPathThatDoesNotExistIsHiddenFromAnonymousCallersAndClosedToTheKey()
      throws Exception {
    anonymous.perform(get("/internal/unknown")).andExpectAll(
        status().isUnauthorized(),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
    anonymous.perform(get("/internal/unknown")
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey)).andExpectAll(
        status().isForbidden(),
        jsonPath("$.detail").value(ACCESS_DENIED));
  }

  /** The default security headers of Spring Security stay on, also for a denied request. */
  @Test
  void theDefaultSecurityHeadersAreWrittenOnADeniedRequest() throws Exception {
    MvcResult result = anonymous.perform(get("/unknown"))
        .andExpect(status().isUnauthorized()).andReturn();

    String cacheControl = result.getResponse().getHeader("Cache-Control");
    assertAll(
        () -> assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options")),
        () -> assertNotNull(cacheControl),
        () -> assertTrue(cacheControl.contains("no-store")));
  }
}
