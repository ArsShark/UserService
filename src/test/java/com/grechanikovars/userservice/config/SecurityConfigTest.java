package com.grechanikovars.userservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grechanikovars.userservice.controller.InternalUserController;
import com.grechanikovars.userservice.controller.PaymentCardController;
import com.grechanikovars.userservice.controller.UserController;
import com.grechanikovars.userservice.security.InternalKeyAuthenticationFilter;
import com.grechanikovars.userservice.service.PaymentCardService;
import com.grechanikovars.userservice.service.UserService;
import jakarta.servlet.DispatcherType;
import java.util.stream.Stream;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Tests {@link SecurityConfig} in a web slice with the real exception handler, the real decoder
 * and the real chain: which kind of caller may reach which endpoint, the format of the 401 and
 * the 403, and the stateless setup. The services are mocks; these tests only ask whether
 * Security lets the request through, and the status that the controller then answers does not
 * matter.
 *
 * <p>The rules are the coarse ones for now: every endpoint under {@code /api} is for an admin
 * only. The rules for an ordinary user, who may see and change only his own data, come with
 * the ownership checks and replace {@link #aUserIsForbiddenOnEveryApiEndpoint(String, String)}.
 *
 * <p>No test here sends a well-formed token, because the decoder would fetch the key set from
 * the Authentication Service; the real way of the tokens is tested in
 * {@code SecurityIntegrationTest}.
 */
@WebMvcTest(controllers = {UserController.class, PaymentCardController.class,
    InternalUserController.class},
    properties = "app.internal.api-key=test-internal-key",
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
class SecurityConfigTest {

  private static final String KEY = "test-internal-key";
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String ACCESS_DENIED = "Access is denied";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  @MockitoBean
  private PaymentCardService paymentCardService;

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

  /** Replaced by the rules of the ordinary user when the ownership checks are added. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("apiEndpoints")
  void aUserIsForbiddenOnEveryApiEndpoint(String method, String path) throws Exception {
    mockMvc.perform(call(method, path).with(user("alice").roles("USER"))).andExpectAll(
        status().isForbidden(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(403),
        jsonPath("$.detail").value(ACCESS_DENIED));
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
