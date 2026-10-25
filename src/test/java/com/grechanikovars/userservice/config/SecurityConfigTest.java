package com.grechanikovars.userservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grechanikovars.userservice.controller.InternalUserController;
import com.grechanikovars.userservice.controller.PaymentCardController;
import com.grechanikovars.userservice.controller.UserController;
import com.grechanikovars.userservice.repository.PaymentCardRepository;
import com.grechanikovars.userservice.repository.UserRepository;
import com.grechanikovars.userservice.security.AccessPolicy;
import com.grechanikovars.userservice.security.InternalKeyAuthenticationFilter;
import com.grechanikovars.userservice.service.PaymentCardService;
import com.grechanikovars.userservice.service.UserService;
import jakarta.servlet.DispatcherType;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tests {@link SecurityConfig} in a web slice with the real exception handler, the real decoder
 * and the real chain: which kind of caller may reach which endpoint, the format of the 401 and
 * the 403, and the stateless setup. The services are mocks; these tests only ask whether
 * Security lets the request through, and the status that the controller then answers does not
 * matter.
 *
 * <p>Three kinds of caller are tried against every endpoint: an admin (passes the chain and
 * every ownership check), a user with the profile id 7 (the endpoints that he may call at all
 * are open to him, and the ownership checks then compare the ids), and a caller of the internal
 * endpoints. The repositories are mocks: card 1 belongs to user 7, card 2 to user 8, card 3 does
 * not exist.
 *
 * <p>No test here sends a well-formed token, because the decoder would fetch the key set from
 * the Authentication Service; the real way of the tokens is tested in
 * {@code SecurityIntegrationTest}.
 */
@WebMvcTest(controllers = {UserController.class, PaymentCardController.class,
    InternalUserController.class},
    properties = "app.internal.api-key=test-internal-key",
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class, AccessPolicy.class})
class SecurityConfigTest {

  private static final String KEY = "test-internal-key";
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String ACCESS_DENIED = "Access is denied";

  private static final String USER_JSON = """
      {"name": "Anna", "surname": "Petrova", "birthDate": "1990-05-17",
       "email": "anna@example.com"}
      """;
  private static final String CARD_JSON = """
      {"number": "4000000000000001", "holder": "ANNA PETROVA", "expirationDate": "2099-12-31"}
      """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  @MockitoBean
  private PaymentCardService paymentCardService;

  @MockitoBean
  private UserRepository userRepository;

  @MockitoBean
  private PaymentCardRepository paymentCardRepository;

  @BeforeEach
  void setUpTheOwners() {
    when(paymentCardRepository.findUserIdById(1L)).thenReturn(Optional.of(7L));
    when(paymentCardRepository.findUserIdById(2L)).thenReturn(Optional.of(8L));
    when(paymentCardRepository.findUserIdById(3L)).thenReturn(Optional.empty());
    when(userRepository.findEmailById(7L)).thenReturn(Optional.of("anna@example.com"));
  }

  /** Every endpoint of the public API with its method; the ids are only placeholders. */
  static Stream<Arguments> apiEndpoints() {
    return Stream.of(
        Arguments.of("POST", "/api/users"),
        Arguments.of("GET", "/api/users"),
        Arguments.of("GET", "/api/users/1"),
        Arguments.of("PUT", "/api/users/1"),
        Arguments.of("PATCH", "/api/users/1/activate"),
        Arguments.of("PATCH", "/api/users/1/deactivate"),
        Arguments.of("DELETE", "/api/users/1"),
        Arguments.of("POST", "/api/users/1/cards"),
        Arguments.of("GET", "/api/users/1/cards"),
        Arguments.of("GET", "/api/cards"),
        Arguments.of("GET", "/api/cards/1"),
        Arguments.of("PUT", "/api/cards/1"),
        Arguments.of("PATCH", "/api/cards/1/activate"),
        Arguments.of("PATCH", "/api/cards/1/deactivate"),
        Arguments.of("DELETE", "/api/cards/1"));
  }

  /** The two internal endpoints with their methods. */
  static Stream<Arguments> internalEndpoints() {
    return Stream.of(
        Arguments.of("POST", "/internal/users"),
        Arguments.of("DELETE", "/internal/users/1"));
  }

  /** What a user with the profile id 7 may do: his own profile, his cards, and card 1. */
  static Stream<Arguments> endpointsOfTheOwner() {
    return Stream.of(
        Arguments.of("GET", "/api/users/7", ""),
        Arguments.of("PUT", "/api/users/7", USER_JSON),
        Arguments.of("POST", "/api/users/7/cards", CARD_JSON),
        Arguments.of("GET", "/api/users/7/cards", ""),
        Arguments.of("GET", "/api/cards/1", ""),
        Arguments.of("PUT", "/api/cards/1", CARD_JSON),
        Arguments.of("PATCH", "/api/cards/1/deactivate", ""),
        Arguments.of("DELETE", "/api/cards/1", ""));
  }

  /** The same endpoints for user 8: forbidden, and card 2 of user 8 does not exist for user 7. */
  static Stream<Arguments> endpointsOfSomebodyElse() {
    return Stream.of(
        Arguments.of("GET", "/api/users/8", "", 403),
        Arguments.of("PUT", "/api/users/8", USER_JSON, 403),
        Arguments.of("POST", "/api/users/8/cards", CARD_JSON, 403),
        Arguments.of("GET", "/api/users/8/cards", "", 403),
        Arguments.of("GET", "/api/cards/2", "", 404),
        Arguments.of("PUT", "/api/cards/2", CARD_JSON, 404),
        Arguments.of("PATCH", "/api/cards/2/deactivate", "", 404),
        Arguments.of("DELETE", "/api/cards/2", "", 404));
  }

  /** What only an admin may do. */
  static Stream<Arguments> adminOnlyEndpoints() {
    return Stream.of(
        Arguments.of("POST", "/api/users"),
        Arguments.of("GET", "/api/users"),
        Arguments.of("PATCH", "/api/users/7/activate"),
        Arguments.of("PATCH", "/api/users/7/deactivate"),
        Arguments.of("DELETE", "/api/users/7"),
        Arguments.of("GET", "/api/cards"),
        Arguments.of("PATCH", "/api/cards/1/activate"));
  }

  /** A user of this service whose token carries the given profile id. */
  private static RequestPostProcessor userWithProfile(Long profileId) {
    return jwt()
        .jwt(token -> {
          token.claim("role", "USER");
          if (profileId != null) {
            token.claim("userId", profileId);
          }
        })
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static MockHttpServletRequestBuilder callWithBody(String method, String path,
      String body) {
    return request(HttpMethod.valueOf(method), path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body.isEmpty() ? "{}" : body);
  }

  private static MockHttpServletRequestBuilder call(String method, String path) {
    return request(HttpMethod.valueOf(method), path)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}");
  }

  private static int statusOf(MvcResult result) {
    return result.getResponse().getStatus();
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("apiEndpoints")
  void anonymousCallsToEveryApiEndpointAreUnauthorized(String method, String path)
      throws Exception {
    mockMvc.perform(call(method, path)).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("apiEndpoints")
  void anAdminPassesTheChainOnEveryApiEndpoint(String method, String path) throws Exception {
    MvcResult result = mockMvc.perform(call(method, path).with(user("root").roles("ADMIN")))
        .andReturn();

    assertAll(
        () -> assertNotEquals(401, statusOf(result)),
        () -> assertNotEquals(403, statusOf(result)));
  }

  /** The endpoints of the user himself pass the chain and the ownership checks. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("endpointsOfTheOwner")
  void aUserPassesOnHisOwnResources(String method, String path, String body) throws Exception {
    MvcResult result = mockMvc.perform(callWithBody(method, path, body).with(userWithProfile(7L)))
        .andReturn();

    assertAll(
        () -> assertNotEquals(401, statusOf(result)),
        () -> assertNotEquals(403, statusOf(result)),
        () -> assertNotEquals(404, statusOf(result)));
  }

  /** The profile of somebody else is a 403; a foreign card is a 404. */
  @ParameterizedTest(name = "{0} {1} -> {3}")
  @MethodSource("endpointsOfSomebodyElse")
  void aUserIsRefusedOnTheResourcesOfSomebodyElse(String method, String path, String body,
      int expectedStatus) throws Exception {
    mockMvc.perform(callWithBody(method, path, body).with(userWithProfile(7L)))
        .andExpectAll(
            status().is(expectedStatus),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    verifyNoInteractions(userService, paymentCardService);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("adminOnlyEndpoints")
  void aUserIsForbiddenOnTheAdminOnlyEndpoints(String method, String path) throws Exception {
    mockMvc.perform(call(method, path).with(userWithProfile(7L))).andExpectAll(
        status().isForbidden(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(403),
        jsonPath("$.detail").value(ACCESS_DENIED));
    verifyNoInteractions(userService, paymentCardService);
  }

  /** A missing card and the card of somebody else have the same status and the same shape. */
  @Test
  void aForeignCardAndAMissingCardLookTheSame() throws Exception {
    MvcResult foreign = mockMvc.perform(get("/api/cards/2").with(userWithProfile(7L)))
        .andReturn();
    MvcResult missing = mockMvc.perform(get("/api/cards/3").with(userWithProfile(7L)))
        .andReturn();

    String foreignBody = foreign.getResponse().getContentAsString();
    String missingBody = missing.getResponse().getContentAsString();
    assertAll(
        () -> assertEquals(404, statusOf(foreign)),
        () -> assertEquals(404, statusOf(missing)),
        () -> assertEquals(foreignBody.replaceAll("\\d+", "N"),
            missingBody.replaceAll("\\d+", "N")));
  }

  /** The email of a user is his login; he may send it back but not change it. */
  @Test
  void aUserMayNotChangeHisEmailButMayRepeatIt() throws Exception {
    String sameEmailOtherCase = USER_JSON.replace("anna@example.com", "ANNA@Example.com");
    String otherEmail = USER_JSON.replace("anna@example.com", "new@example.com");

    MvcResult repeated = mockMvc.perform(callWithBody("PUT", "/api/users/7", sameEmailOtherCase)
        .with(userWithProfile(7L))).andReturn();
    mockMvc.perform(callWithBody("PUT", "/api/users/7", otherEmail).with(userWithProfile(7L)))
        .andExpectAll(
            status().isForbidden(),
            jsonPath("$.detail").value(ACCESS_DENIED));

    assertAll(
        () -> assertNotEquals(403, statusOf(repeated)),
        () -> assertNotEquals(400, statusOf(repeated)));
  }

  /** An admin may change the email of a user, and no lookup is needed for an admin. */
  @Test
  void anAdminMayChangeAnEmailAndNeedsNoOwnershipLookup() throws Exception {
    String otherEmail = USER_JSON.replace("anna@example.com", "new@example.com");

    MvcResult result = mockMvc.perform(callWithBody("PUT", "/api/users/7", otherEmail)
        .with(user("root").roles("ADMIN"))).andReturn();
    mockMvc.perform(get("/api/cards/2").with(user("root").roles("ADMIN"))).andReturn();

    assertNotEquals(403, statusOf(result));
    verifyNoInteractions(paymentCardRepository);
  }

  /** A token without a profile id is valid, but it owns nothing and the database is not asked. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("endpointsOfTheOwner")
  void aUserWithoutAProfileOwnsNothing(String method, String path, String body)
      throws Exception {
    mockMvc.perform(callWithBody(method, path, body).with(userWithProfile(null)))
        .andExpectAll(
            status().isForbidden(),
            jsonPath("$.detail").value(ACCESS_DENIED));
    verifyNoInteractions(paymentCardRepository);
  }

  /** A user who is authenticated in another way than by a token has no profile either. */
  @Test
  void aCallerWithoutATokenOwnsNothing() throws Exception {
    mockMvc.perform(get("/api/users/7").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
  }

  /** The key has a meaning only under /internal: elsewhere it authenticates nobody. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("apiEndpoints")
  void theInternalKeyOpensNoApiEndpoint(String method, String path) throws Exception {
    mockMvc.perform(call(method, path).header(InternalKeyAuthenticationFilter.HEADER, KEY))
        .andExpectAll(
            status().isUnauthorized(),
            jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("internalEndpoints")
  void theRightKeyOpensTheInternalEndpoints(String method, String path) throws Exception {
    MvcResult result = mockMvc.perform(call(method, path)
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andReturn();

    assertAll(
        () -> assertNotEquals(401, statusOf(result)),
        () -> assertNotEquals(403, statusOf(result)));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("internalEndpoints")
  void aMissingKeyIsAnUnauthorizedProblem(String method, String path) throws Exception {
    mockMvc.perform(call(method, path)).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  /** A wrong key gets the very same answer as a missing key: the caller learns nothing. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("internalEndpoints")
  void aWrongKeyGetsTheSameAnswerAsAMissingKey(String method, String path) throws Exception {
    mockMvc.perform(call(method, path).header(InternalKeyAuthenticationFilter.HEADER, "wrong"))
        .andExpectAll(
            status().isUnauthorized(),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.status").value(401),
            jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  /** A caller with a user or admin role is authenticated, but the internal role is not theirs. */
  @ParameterizedTest
  @ValueSource(strings = {"USER", "ADMIN"})
  void aCallerWithoutTheInternalRoleIsForbiddenOnAnInternalPath(String role) throws Exception {
    mockMvc.perform(call("POST", "/internal/users").with(user("alice").roles(role)))
        .andExpectAll(
            status().isForbidden(),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.status").value(403),
            jsonPath("$.detail").value(ACCESS_DENIED));
  }

  /** Only the two endpoints are opened, each with its method; the rest of /internal is closed. */
  @ParameterizedTest(name = "{0} {1}")
  @ValueSource(strings = {"GET /internal/users", "PUT /internal/users/1",
      "GET /internal/users/1", "POST /internal/other", "DELETE /internal/users"})
  void everythingElseUnderInternalStaysClosedEvenWithTheRightKey(String endpoint)
      throws Exception {
    String[] parts = endpoint.split(" ");

    mockMvc.perform(call(parts[0], parts[1]).header(InternalKeyAuthenticationFilter.HEADER, KEY))
        .andExpectAll(
            status().isForbidden(),
            jsonPath("$.detail").value(ACCESS_DENIED));
  }

  @Test
  void anAnonymousCallToAnUnknownInternalPathLooksLikeAnyOther() throws Exception {
    mockMvc.perform(get("/internal/unknown")).andExpectAll(
        status().isUnauthorized(),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  @Test
  void anonymousRequestToAnUnknownPathIsAnUnauthorizedProblem() throws Exception {
    mockMvc.perform(get("/unknown")).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  @Test
  void authenticatedUserOnAnUnknownPathIsAForbiddenProblem() throws Exception {
    mockMvc.perform(get("/unknown").with(user("alice").roles("USER"))).andExpectAll(
        status().isForbidden(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(403),
        jsonPath("$.detail").value(ACCESS_DENIED));
  }

  /** A token that cannot even be parsed is rejected before the key set is needed. */
  @ParameterizedTest
  @ValueSource(strings = {"garbage", "a.b.c", "Bearer"})
  void aMalformedBearerTokenIsAnUnauthorizedProblem(String token) throws Exception {
    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION,
        String.format("Bearer %s", token))).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  /** A default chain would save the denied request in a session and set a cookie. */
  @Test
  void aDeniedRequestCreatesNoSessionAndNoCookie() throws Exception {
    MvcResult result = mockMvc.perform(get("/unknown")).andReturn();

    assertAll(
        () -> assertNull(result.getRequest().getSession(false)),
        () -> assertNull(result.getResponse().getHeader("Set-Cookie")));
  }

  /**
   * The container forwards a failed request to the error page with the ERROR dispatch type; that
   * forward must not be denied again. Here Security lets it pass, so Spring MVC answers and
   * finds no handler: 404 instead of the 401 that a REQUEST dispatch gets.
   */
  @Test
  void anErrorDispatchIsNotDeniedBySecurity() throws Exception {
    mockMvc.perform(get("/unknown").with(request -> {
      request.setDispatcherType(DispatcherType.ERROR);
      return request;
    })).andExpect(status().isNotFound());
  }
}
