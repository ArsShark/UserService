package com.grechanikovars.userservice.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.grechanikovars.userservice.TestTokens;
import com.grechanikovars.userservice.controller.UserController;
import com.grechanikovars.userservice.repository.PaymentCardRepository;
import com.grechanikovars.userservice.repository.UserRepository;
import com.grechanikovars.userservice.security.AccessPolicy;
import com.grechanikovars.userservice.service.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the tests that put the key set of the Authentication Service in a state of the test's
 * choice (not running, running and then stopped) and send real tokens through the whole security
 * chain: the real decoder, the real chain and the real error handler, with the controller and the
 * service behind them as mocks.
 *
 * <p>Each subclass sets {@code app.security.jwks-uri} with a {@code @DynamicPropertySource}
 * method, so each gets its own context and its own cache of the key. The clock stands one minute
 * after the {@code iat} of the tokens of {@link TestTokens}, so a default token is valid.
 *
 * <p>The request is {@code GET /api/users/7}: the profile of the user whose profile id is 7,
 * which is the default caller of {@link TestTokens#access()}. It reaches the controller without
 * a database, because the ownership of a user is a comparison of ids.
 */
@WebMvcTest(controllers = UserController.class,
    properties = "app.internal.api-key=test-internal-key",
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, AccessPolicy.class,
    AbstractKeySetSliceTest.FixedClock.class})
abstract class AbstractKeySetSliceTest {

  protected static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  @Autowired
  protected MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  @MockitoBean
  private UserRepository userRepository;

  @MockitoBean
  private PaymentCardRepository paymentCardRepository;

  /**
   * Sends the request with a token.
   *
   * @param token a compact serialized token
   * @return the result of the request
   */
  protected ResultActions getProfileWith(String token) throws Exception {
    return mockMvc.perform(get("/api/users/7")
        .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token)));
  }

  /**
   * Sends the request without any token.
   *
   * @return the result of the request
   */
  protected ResultActions getProfileWithoutToken() throws Exception {
    return mockMvc.perform(get("/api/users/7"));
  }

  /**
   * Reads the body of an answer as text.
   *
   * @param actions the result of a request
   * @return the body
   */
  protected static String bodyOf(ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /** The clock of the slice: one minute after the {@code iat} of the test tokens. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {

    @Bean
    Clock clock() {
      return Clock.fixed(TestTokens.ISSUED_AT.plusSeconds(60), ZoneOffset.UTC);
    }
  }
}
