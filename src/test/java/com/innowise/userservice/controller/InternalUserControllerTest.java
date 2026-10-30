package com.innowise.userservice.controller;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.innowise.userservice.exception.UserNotFoundException;
import com.innowise.userservice.security.InternalKeyAuthenticationFilter;
import com.innowise.userservice.service.UserService;
import java.time.Instant;
import java.time.LocalDate;
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
    when(userService.create(any(UserRequest.class))).thenReturn(user(7));

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
    verify(userService).create(request.capture());
    assertAll(
        () -> assertEquals("Anna", request.getValue().name()),
        () -> assertEquals("anna@example.com", request.getValue().email()));
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
    when(userService.create(any(UserRequest.class))).thenThrow(new EmailAlreadyExistsException());

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
  void aRequestWithoutTheKeyNeverReachesTheService() throws Exception {
    mockMvc.perform(post("/internal/users")
        .contentType(MediaType.APPLICATION_JSON)
        .content(VALID_USER_JSON)).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/internal/users/{id}", 7)).andExpect(status().isUnauthorized());

    verifyNoInteractions(userService);
  }
}
