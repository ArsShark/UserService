package com.innowise.userservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.config.ClockConfig;
import com.innowise.userservice.config.JwtConfig;
import com.innowise.userservice.config.SecurityConfig;
import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.dto.response.UserResponse;
import com.innowise.userservice.exception.EmailAlreadyExistsException;
import com.innowise.userservice.exception.RegistrationIdAlreadyUsedException;
import com.innowise.userservice.exception.UserNotFoundException;
import com.innowise.userservice.security.InternalKeyAuthenticationFilter;
import com.innowise.userservice.service.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests of {@link InternalUserController}. The service is a mock; the security
 * chain is real, so every request carries the internal key, and one test proves that a request
 * without it never reaches the service.
 */
@WebMvcTest(controllers = InternalUserController.class,
    properties = "app.internal.api-key=test-internal-key",
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
class InternalUserControllerTest {

  private static final String KEY = "test-internal-key";
  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String VALID_USER_JSON = """
      {
        "name": "Anna",
        "surname": "Petrova",
        "birthDate": "1990-05-17",
        "email": "anna@example.com"
      }
      """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  private static UserResponse user(long id) {
    return new UserResponse(id, "Anna", "Petrova", LocalDate.of(1990, 5, 17),
        "anna@example.com", true, NOW, NOW);
  }

  @Test
  void createReturns201WithTheProfileAndWithoutALocation() throws Exception {
    when(userService.create(any(UserRequest.class), isNull())).thenReturn(user(7));

    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpectAll(
        status().isCreated(),
        header().doesNotExist("Location"),
        jsonPath("$.id").value(7),
        jsonPath("$.email").value("anna@example.com"),
        jsonPath("$.active").value(true));

    ArgumentCaptor<UserRequest> request = ArgumentCaptor.forClass(UserRequest.class);
    verify(userService).create(request.capture(), isNull());
    assertAll(
        () -> assertEquals("Anna", request.getValue().name()),
        () -> assertEquals("anna@example.com", request.getValue().email()));
  }

  @Test
  void createPassesTheRegistrationIdOfTheHeaderAndNeverReturnsIt() throws Exception {
    UUID registrationId = UUID.fromString("0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11");
    when(userService.create(any(UserRequest.class), any(UUID.class))).thenReturn(user(7));

    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .header(InternalUserController.REGISTRATION_ID_HEADER, registrationId.toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpectAll(
        status().isCreated(),
        content().string(not(containsString(registrationId.toString()))));

    verify(userService).create(any(UserRequest.class), eq(registrationId));
  }

  /** The header must be a UUID: nothing else can be the id of an attempt. */
  @Test
  void createRejectsARegistrationIdThatIsNotAUuidWith400() throws Exception {
    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .header(InternalUserController.REGISTRATION_ID_HEADER, "not-a-uuid")
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpectAll(
        status().isBadRequest(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(userService);
  }

  @Test
  void createAnswers409ForARegistrationIdThatIsAlreadyUsed() throws Exception {
    when(userService.create(any(UserRequest.class), any(UUID.class)))
        .thenThrow(new RegistrationIdAlreadyUsedException());

    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .header(InternalUserController.REGISTRATION_ID_HEADER,
            "0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11")
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpectAll(
        status().isConflict(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(409));
  }

  @Test
  void createRejectsAnInvalidBodyWith400AndTheListOfFields() throws Exception {
    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpectAll(
        status().isBadRequest(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.errors").isNotEmpty());

    verifyNoInteractions(userService);
  }

  @Test
  void createAnswers409ForATakenEmail() throws Exception {
    when(userService.create(any(UserRequest.class), any()))
        .thenThrow(new EmailAlreadyExistsException());

    mockMvc.perform(post("/internal/users")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpectAll(
        status().isConflict(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(409));
  }

  @Test
  void deleteReturns204() throws Exception {
    mockMvc.perform(delete("/internal/users/{id}", 7)
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpect(status().isNoContent());

    verify(userService).delete(7L);
  }

  @Test
  void deleteByRegistrationIdReturns204() throws Exception {
    UUID registrationId = UUID.fromString("0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11");

    mockMvc.perform(delete("/internal/users/by-registration/{registrationId}", registrationId)
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpect(status().isNoContent());

    verify(userService).deleteByRegistrationId(registrationId);
  }

  /** The caller treats this answer as "already gone": the profile may never have been created. */
  @Test
  void deleteByRegistrationIdAnswers404ForAnUnknownIdWithoutEchoingIt() throws Exception {
    UUID registrationId = UUID.fromString("0b9c4b5e-6f3e-4a56-9d3a-5f6f0d2a7c11");
    doThrow(UserNotFoundException.forRegistration()).when(userService)
        .deleteByRegistrationId(registrationId);

    mockMvc.perform(delete("/internal/users/by-registration/{registrationId}", registrationId)
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpectAll(
        status().isNotFound(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(404),
        content().string(not(containsString(registrationId.toString()))));
  }

  @Test
  void deleteByRegistrationIdRejectsAnIdThatIsNotAUuidWith400() throws Exception {
    mockMvc.perform(delete("/internal/users/by-registration/{registrationId}", "not-a-uuid")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpectAll(
        status().isBadRequest(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(userService);
  }

  /** The caller of the rollback treats this answer as "already rolled back". */
  @Test
  void deleteAnswers404ForAMissingUser() throws Exception {
    doThrow(new UserNotFoundException(7L)).when(userService).delete(7L);

    mockMvc.perform(delete("/internal/users/{id}", 7)
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpectAll(
        status().isNotFound(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(404));
  }

  @Test
  void deleteRejectsANonNumericIdWith400() throws Exception {
    mockMvc.perform(delete("/internal/users/abc")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpect(status().isBadRequest());

    verifyNoInteractions(userService);
  }

  @Test
  void getByEmailReturns200WithTheProfile() throws Exception {
    when(userService.getByEmail("anna@example.com")).thenReturn(user(7));

    mockMvc.perform(get("/internal/users/by-email")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .param("email", "anna@example.com")).andExpectAll(
        status().isOk(),
        jsonPath("$.id").value(7),
        jsonPath("$.email").value("anna@example.com"),
        jsonPath("$.active").value(true),
        jsonPath("$.cards").doesNotExist());
  }

  /** The controller passes the email on untouched; normalizing it is the job of the service. */
  @Test
  void getByEmailPassesTheEmailOnAsItCame() throws Exception {
    when(userService.getByEmail(" Anna+Shop@Example.com ")).thenReturn(user(7));

    mockMvc.perform(get("/internal/users/by-email")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .param("email", " Anna+Shop@Example.com ")).andExpect(status().isOk());

    verify(userService).getByEmail(" Anna+Shop@Example.com ");
  }

  @Test
  void getByEmailAnswers404ForAnUnknownEmailAndNeverEchoesIt() throws Exception {
    when(userService.getByEmail("nobody@example.com")).thenThrow(UserNotFoundException.forEmail());

    mockMvc.perform(get("/internal/users/by-email")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)
        .param("email", "nobody@example.com")).andExpectAll(
        status().isNotFound(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(404),
        content().string(not(containsString("nobody"))));
  }

  @Test
  void getByEmailWithoutTheParameterIs400() throws Exception {
    mockMvc.perform(get("/internal/users/by-email")
        .header(InternalKeyAuthenticationFilter.HEADER, KEY)).andExpectAll(
        status().isBadRequest(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(userService);
  }

  @Test
  void aRequestWithoutTheKeyNeverReachesTheService() throws Exception {
    mockMvc.perform(post("/internal/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/internal/users/{id}", 7)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/internal/users/by-email").param("email", "anna@example.com"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(userService);
  }
}
