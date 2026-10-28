package com.grechanikovars.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks that the links of the service follow the {@code X-Forwarded-*} headers of the API
 * Gateway: a client that reaches the service through the Gateway must see the address of the
 * Gateway in the {@code Location} header, not the internal host and port of the service.
 */
class ForwardedHeadersIntegrationTest extends AbstractIntegrationTest {

  private static final String USER_JSON = """
      {"name":"Ivan","surname":"Petrov","birthDate":"1990-05-17","email":"ivan@example.com"}""";

  @Test
  void theLocationOfACreatedUserUsesTheHostAndProtocolOfTheGateway() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/users")
        .header("X-Forwarded-Host", "shop.example.test")
        .header("X-Forwarded-Proto", "https")
        .contentType(MediaType.APPLICATION_JSON)
        .content(USER_JSON))
      .andExpect(status().isCreated())
      .andReturn();

    String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
    assertAll(
      () -> assertTrue(location.startsWith("https://shop.example.test/api/users/")),
      () -> assertFalse(location.contains("localhost")));
  }

  @Test
  void withoutForwardedHeadersTheLocationKeepsTheHostOfTheRequest() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(USER_JSON))
      .andExpect(status().isCreated())
      .andReturn();

    String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
    assertTrue(location.startsWith("http://localhost/api/users/"));
  }
}
