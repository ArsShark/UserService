package com.innowise.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.LogCapture;
import com.innowise.userservice.TestKeys;
import com.innowise.userservice.TestTokens;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves the rule that no token, key, email or card number ever reaches the log, for the whole
 * service at once.
 *
 * <p>An appender is attached to the logger that serves the whole application, then one run goes
 * through the endpoints with successes and with every kind of failure: valid tokens of a user and
 * of an admin, bad tokens of every kind, the internal key right and wrong, refused access to
 * somebody else's resources, validation failures and conflicts. Afterwards each secret that the
 * run used or produced is searched for in everything that was logged, from any library.
 *
 * <p>The test checks itself: it also requires lines that the run must have written, so a capture
 * that sees nothing fails instead of passing for nothing.
 */
class SecretsNotLoggedIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private WebApplicationContext webContext;

  private static final String INTERNAL_KEY = "test-internal-key";
  private static final String WRONG_INTERNAL_KEY = "wrong-internal-key";
  private static final String ANNA_EMAIL = "anna.secret@example.com";
  private static final String BORIS_EMAIL = "boris.secret@example.com";
  private static final String CHANGED_EMAIL = "changed.secret@example.com";
  private static final String CAROL_EMAIL = "carol.secret@example.com";
  private static final String INVALID_EMAIL = "not-an-email-4711";
  private static final String ANNA_CARD = cardNumber(1);
  private static final String BORIS_CARD = cardNumber(2);
  private static final String INVALID_CARD = "1234-secret-card";

  @Test
  void noSecretOfAnyKindReachesTheLog() throws Exception {
    String annaToken;
    List<String> badTokens;
    LogCapture logs = LogCapture.attach();
    try {
      long annaId = createUser("Anna", "Petrova", ANNA_EMAIL);
      long borisId = createUser("Boris", "Ivanov", BORIS_EMAIL);
      long annaCard = createCard(annaId, ANNA_CARD);
      long borisCard = createCard(borisId, BORIS_CARD);

      // A user with a valid token: his own resources, then those of somebody else.
      annaToken = TestTokens.access().userId(annaId).subject("11").email(ANNA_EMAIL).build();
      as(annaToken, get("/api/users/{id}", annaId)).andExpect(status().isOk());
      as(annaToken, get("/api/users/{id}/cards", annaId)).andExpect(status().isOk());
      as(annaToken, get("/api/cards/{id}", annaCard)).andExpect(status().isOk());
      as(annaToken, get("/api/users/{id}", borisId)).andExpect(status().isForbidden());
      as(annaToken, get("/api/cards/{id}", borisCard)).andExpect(status().isNotFound());
      as(annaToken, get("/api/users")).andExpect(status().isForbidden());
      as(annaToken, json(put("/api/users/{id}", annaId),
          userJson("Anna", "Petrova", CHANGED_EMAIL))).andExpect(status().isForbidden());

      // Failures that carry a secret in the request: conflicts and invalid values.
      mockMvc.perform(json(post("/api/users"), userJson("Anna", "Petrova", ANNA_EMAIL)))
          .andExpect(status().isConflict());
      mockMvc.perform(json(post("/api/users/{id}/cards", borisId),
          cardJson(ANNA_CARD, "IVAN PETROV", "2032-12-31"))).andExpect(status().isConflict());
      mockMvc.perform(json(post("/api/users"), userJson("Anna", "Petrova", INVALID_EMAIL)))
          .andExpect(status().isBadRequest());
      mockMvc.perform(json(post("/api/users/{id}/cards", borisId),
          cardJson(INVALID_CARD, "IVAN PETROV", "2032-12-31")))
          .andExpect(status().isBadRequest());

      // Every kind of bad token.
      badTokens = badTokens();
      for (String badToken : badTokens) {
        as(badToken, get("/api/users")).andExpect(status().isUnauthorized());
      }

      // The internal endpoints: the right key, a repeat, a wrong key and the rollback.
      String carol = userJson("Carol", "Smith", CAROL_EMAIL);
      ResultActions created = internal(post("/internal/users"), INTERNAL_KEY, carol);
      long carolId = idOf(created.andExpect(status().isCreated()).andReturn());
      internal(post("/internal/users"), INTERNAL_KEY, carol).andExpect(status().isConflict());
      internal(post("/internal/users"), WRONG_INTERNAL_KEY, carol)
          .andExpect(status().isUnauthorized());
      internal(delete("/internal/users/{id}", carolId), INTERNAL_KEY, null)
          .andExpect(status().isNoContent());
      internal(delete("/internal/users/{id}", carolId), INTERNAL_KEY, null)
          .andExpect(status().isNotFound());
    } finally {
      logs.close();
    }

    List<String> lines = logs.lines();
    Map<String, String> secrets = secretsOfTheRun(annaToken, badTokens);
    List<String> leaked = secrets.entrySet().stream()
        .filter(secret -> lines.stream().anyMatch(line -> line.contains(secret.getValue())))
        .map(Map.Entry::getKey)
        .toList();
    assertAll(
        () -> assertTrue(containsLine(lines, "Created user with id"), "the capture sees writes"),
        () -> assertTrue(containsLine(lines, "Request rejected with status 401"), "and the 401"),
        () -> assertTrue(containsLine(lines, "Request rejected with status 403"), "and the 403"),
        () -> assertTrue(containsLine(lines, "Request rejected with status 409"), "and the 409"),
        () -> assertEquals(List.of(), leaked, "secrets found in the log"));
  }

  /** Every secret that the run used or produced, by a label that names it in a failure. */
  private static Map<String, String> secretsOfTheRun(String annaToken, List<String> badTokens)
      throws Exception {
    Map<String, String> secrets = new LinkedHashMap<>();
    secrets.put("user token", annaToken);
    secrets.put("signature of the user token", partOf(annaToken, 2));
    secrets.put("payload of the user token", partOf(annaToken, 1));
    secrets.put("jti of the user token", jtiOf(annaToken));
    secrets.put("admin token", ADMIN_TOKEN);
    secrets.put("signature of the admin token", partOf(ADMIN_TOKEN, 2));
    secrets.put("payload of the admin token", partOf(ADMIN_TOKEN, 1));
    secrets.put("jti of the admin token", jtiOf(ADMIN_TOKEN));
    for (int i = 0; i < badTokens.size(); i++) {
      secrets.put(String.format("bad token %d", i), badTokens.get(i));
    }
    secrets.put("internal key", INTERNAL_KEY);
    secrets.put("wrong internal key", WRONG_INTERNAL_KEY);
    String privateKey =
        Base64.getEncoder().encodeToString(TestKeys.KEY_PAIR.getPrivate().getEncoded());
    secrets.put("private key", privateKey);
    secrets.put("part of the private key", privateKey.substring(100, 160));
    secrets.put("anna email", ANNA_EMAIL);
    secrets.put("boris email", BORIS_EMAIL);
    secrets.put("changed email", CHANGED_EMAIL);
    secrets.put("carol email", CAROL_EMAIL);
    secrets.put("rejected email", INVALID_EMAIL);
    secrets.put("anna card number", ANNA_CARD);
    secrets.put("boris card number", BORIS_CARD);
    secrets.put("rejected card number", INVALID_CARD);
    return secrets;
  }

  /** Tokens that must be refused: each of them is a different way to be wrong. */
  private static List<String> badTokens() {
    String[] user = TestTokens.access().build().split("\\.");
    String[] admin = TestTokens.admin().build().split("\\.");
    return List.of(
        TestTokens.admin().issuedAt(START.minus(TestTokens.ACCESS_TTL))
            .expiresAt(START.minusSeconds(1)).build(),
        TestTokens.admin().tokenType("refresh").build(),
        TestTokens.admin().issuer("somebody-else").build(),
        TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).build(),
        TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).keyId("unknown-key").build(),
        String.format("%s.%s.%s", user[0], admin[1], user[2]),
        TestTokens.admin().expiresAt(TestTokens.ISSUED_AT.plus(Duration.ofDays(3650)))
            .tokenType(null).build(),
        "not.a.token");
  }

  private ResultActions as(String token, MockHttpServletRequestBuilder request)
      throws Exception {
    return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)));
  }

  /** An internal call: no token at all, which the default MockMvc of the tests would add. */
  private ResultActions internal(MockHttpServletRequestBuilder request, String key, String body)
      throws Exception {
    MockMvc withoutToken =
        MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    request.header("X-Internal-Key", key);
    return withoutToken.perform(body == null ? request : json(request, body));
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
      String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String partOf(String token, int index) {
    return token.split("\\.")[index];
  }

  private static String jtiOf(String token) throws Exception {
    return SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
  }

  private static boolean containsLine(List<String> lines, String text) {
    return lines.stream().anyMatch(line -> line.contains(text));
  }
}
