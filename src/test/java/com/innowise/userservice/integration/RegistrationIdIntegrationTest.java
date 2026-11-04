package com.innowise.userservice.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Tests the registration id of a profile through the real chain, PostgreSQL and Redis: it is
 * stored when the internal endpoint receives it, never returned, never set by the public
 * endpoint, and the UNIQUE constraint keeps two profiles from sharing it. The deletion by this
 * id removes only the profile of that attempt, with its cards and its cache entry.
 */
class RegistrationIdIntegrationTest extends AbstractIntegrationTest {

  private static final String ID = "0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11";
  private static final String OTHER_ID = "6d0f4a8e-2f0e-4c47-8f55-1b7c9c3d5e22";

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

  private ResultActions deleteInternally(String registrationId) throws Exception {
    return anonymous.perform(delete("/internal/users/by-registration/{id}", registrationId)
        .header(InternalKeyAuthenticationFilter.HEADER, internalKey));
  }

  /** Two profiles of two attempts: the id picks the right one, and its cards go with it. */
  @Test
  void theDeletionRemovesOnlyTheProfileOfThatAttemptWithItsCards() throws Exception {
    long first = idOf(createInternally("anna@example.com", ID)
        .andExpect(status().isCreated()).andReturn());
    long second = idOf(createInternally("ivan@example.com", OTHER_ID)
        .andExpect(status().isCreated()).andReturn());
    createCard(first, "4000000000000001");
    createCard(second, "4000000000000002");

    deleteInternally(ID).andExpect(status().isNoContent());

    assertAll(
        () -> assertEquals(1L, countRows("users")),
        () -> assertEquals(List.of(OTHER_ID), storedRegistrationIds()),
        () -> assertEquals(1L, countRows("payment_cards")));
  }

  /** The caller repeats a deletion that may already have worked: the 404 is "already gone". */
  @Test
  void aSecondDeletionAnswers404WithoutTheIdAndChangesNothing() throws Exception {
    createInternally("anna@example.com", ID).andExpect(status().isCreated());
    deleteInternally(ID).andExpect(status().isNoContent());

    deleteInternally(ID).andExpectAll(
        status().isNotFound(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        content().string(not(containsString(ID))));

    assertEquals(0L, countRows("users"));
  }

  /**
   * A profile that an admin created has no registration id, so no id can delete it: the
   * undoing of a registration never reaches a profile of somebody else.
   */
  @Test
  void aProfileWithoutARegistrationIdCannotBeDeletedByAnyId() throws Exception {
    createUser("Anna", "Petrova", "anna@example.com");

    deleteInternally(ID).andExpect(status().isNotFound());

    assertEquals(1L, countRows("users"));
  }

  @Test
  void theDeletionEvictsTheCachedProfile() throws Exception {
    long id = idOf(createInternally("anna@example.com", ID)
        .andExpect(status().isCreated()).andReturn());
    mockMvc.perform(get("/api/users/{id}", id)).andExpect(status().isOk());
    assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(userCacheKey(id))), "cached by the read");

    deleteInternally(ID).andExpect(status().isNoContent());

    assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(userCacheKey(id))));
  }

  @Test
  void aWrongKeyAndAnAdminTokenCannotDeleteAndNothingIsRemoved() throws Exception {
    createInternally("anna@example.com", ID).andExpect(status().isCreated());

    anonymous.perform(delete("/internal/users/by-registration/{id}", ID)
        .header(InternalKeyAuthenticationFilter.HEADER, "wrong-key"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/internal/users/by-registration/{id}", ID))
        .andExpect(status().isForbidden());

    assertEquals(1L, countRows("users"));
  }

  @Test
  void aDeletionWithAnIdThatIsNotAUuidIs400() throws Exception {
    deleteInternally("not-a-uuid").andExpect(status().isBadRequest());
  }
}
