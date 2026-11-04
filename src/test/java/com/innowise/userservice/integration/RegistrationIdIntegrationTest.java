package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.controller.InternalUserController;
import com.innowise.userservice.security.InternalKeyAuthenticationFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the registration id of a profile through the real chain and PostgreSQL: it is stored when
 * the internal endpoint receives it, never returned, never set by the public endpoint, and the
 * UNIQUE constraint keeps two profiles from sharing it.
 */
class RegistrationIdIntegrationTest extends AbstractIntegrationTest {

  private static final String ID = "0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11";

  @Value("${app.internal.api-key}")
  private String internalKey;

  @Autowired
  private WebApplicationContext webContext;

  /** A MockMvc without the default admin token: the internal key is the only credential. */
  private MockMvc anonymous;

  @BeforeEach
  void buildAnonymousMockMvc() {
    anonymous = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
  }

  private ResultActions createInternally(String email, String registrationId) throws Exception {
    var request = post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(userJson("Anna", "Petrova", email));
    if (registrationId != null) {
      request.header(InternalUserController.REGISTRATION_ID_HEADER, registrationId);
    }
    return anonymous.perform(request);
  }

  private List<String> storedRegistrationIds() {
    return jdbcTemplate.queryForList(
        "SELECT CAST(registration_id AS text) FROM users ORDER BY id", String.class);
  }

  @Test
  void theInternalCreationStoresTheRegistrationIdAndNeverReturnsIt() throws Exception {
    createInternally("anna@example.com", ID).andExpectAll(
        status().isCreated(),
        content().string(not(containsString(ID))),
        jsonPath("$.registrationId").doesNotExist());

    assertEquals(List.of(ID), storedRegistrationIds());
  }

  @Test
  void aCreationWithoutTheHeaderLeavesTheColumnEmpty() throws Exception {
    createInternally("anna@example.com", null).andExpect(status().isCreated());

    assertNull(storedRegistrationIds().get(0));
  }

  /** An admin creates profiles through the public endpoint, which has no registration id. */
  @Test
  void thePublicCreationNeverSetsARegistrationId() throws Exception {
    createUser("Anna", "Petrova", "anna@example.com");

    assertNull(storedRegistrationIds().get(0));
  }

  /** Two profiles with different emails and one id: the constraint is the guard, a 409. */
  @Test
  void theSameRegistrationIdTwiceIsA409AndWritesNothing() throws Exception {
    createInternally("anna@example.com", ID).andExpect(status().isCreated());

    createInternally("ivan@example.com", ID).andExpectAll(
        status().isConflict(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.detail").value("A profile was already created for this registration"));

    assertAll(
        () -> assertEquals(1, storedRegistrationIds().size()),
        () -> assertEquals(1L, countRows("users")));
  }

  @Test
  void aRegistrationIdThatIsNotAUuidIs400AndWritesNothing() throws Exception {
    createInternally("anna@example.com", "not-a-uuid").andExpect(status().isBadRequest());

    assertEquals(0L, countRows("users"));
  }
}
