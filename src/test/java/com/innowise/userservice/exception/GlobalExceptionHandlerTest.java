package com.innowise.userservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests of {@link GlobalExceptionHandler}.
 *
 * <p>The handler is exercised through a standalone MockMvc and a throwaway controller, so no
 * Spring context is started and the tests do not depend on the real controllers.
 */
class GlobalExceptionHandlerTest {

  private ThrowingController controller;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller = new ThrowingController();
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
  }

  static Stream<RuntimeException> notFoundExceptions() {
    return Stream.of(new UserNotFoundException(5L), new PaymentCardNotFoundException(7L));
  }

  static Stream<RuntimeException> conflictExceptions() {
    return Stream.of(
      new EmailAlreadyExistsException(),
      new CardNumberAlreadyExistsException(),
      new CardLimitExceededException(5L, 5));
  }

  @ParameterizedTest
  @MethodSource("notFoundExceptions")
  void mapsNotFoundExceptionsTo404(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(404),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @ParameterizedTest
  @MethodSource("conflictExceptions")
  void mapsConflictExceptionsTo409(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(409),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @Test
  void mapsInvalidSortPropertyTo400() throws Exception {
    InvalidSortPropertyException exception = new InvalidSortPropertyException(Set.of("id", "name"));
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @ParameterizedTest
  @CsvSource({
    "uq_users_email, A user with this email already exists",
    "uq_payment_cards_number, A payment card with this number already exists"
  })
  void mapsUniqueConstraintViolationsTo409(String constraint, String expectedDetail)
    throws Exception {
    controller.exceptionToThrow = integrityViolation(constraint);

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value(expectedDetail),
      content().string(not(containsString("john@example.com"))));
  }

  @Test
  void mapsOtherIntegrityViolationsTo500WithoutRowData() throws Exception {
    controller.exceptionToThrow = integrityViolation("chk_users_name_length");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("john@example.com"))));
  }

  @Test
  void mapsIntegrityViolationWithoutConstraintNameTo500() throws Exception {
    controller.exceptionToThrow = new DataIntegrityViolationException("no known cause");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void hidesInternalDetailsOfUnexpectedExceptions() throws Exception {
    controller.exceptionToThrow = new IllegalStateException("secret internal detail");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("secret internal detail"))));
  }

  @Test
  void listsInvalidFieldsWithoutRejectedValues() throws Exception {
    String body = "{\"name\": \"\", \"email\": \"not-an-email\"}";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Request validation failed"),
      jsonPath("$.errors.length()").value(2),
      jsonPath("$.errors[0].field").value("email"),
      jsonPath("$.errors[1].field").value("name"),
      content().string(not(containsString("not-an-email"))));
  }

  @Test
  void answersMalformedJsonWith400() throws Exception {
    String body = "{\"name\": \"John\", \"email\": ";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      content().string(not(containsString("John"))));
  }

  @Test
  void answersWrongParameterTypeWith400() throws Exception {
    mockMvc.perform(get("/test/items/abc")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400));
  }

  @Test
  void answersUnsupportedMethodWith405() throws Exception {
    mockMvc.perform(post("/test/items/1")).andExpectAll(
      status().isMethodNotAllowed(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(405));
  }

  /** The constant detail never says why, and the message of the exception is not sent. */
  @Test
  void mapsAuthenticationFailuresTo401WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new BadCredentialsException("token secret-value expired");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isUnauthorized(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(401),
      jsonPath("$.detail").value("Authentication is required"),
      content().string(not(containsString("secret-value"))));
  }

  /** Without an explicit handler the catch-all would turn this into a 500. */
  @Test
  void mapsAccessDeniedTo403WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new AccessDeniedException("card 42 belongs to user 9");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isForbidden(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(403),
      jsonPath("$.detail").value("Access is denied"),
      content().string(not(containsString("card 42"))));
  }

  /** Builds the exception Spring raises for a violated constraint, with a row in its message. */
  private static DataIntegrityViolationException integrityViolation(String constraint) {
    SQLException sqlException = new SQLException("Key (email)=(john@example.com) already exists");
    return new DataIntegrityViolationException("could not execute statement",
      new ConstraintViolationException("could not execute statement", sqlException, constraint));
  }

  /** Request body with two constrained fields. */
  public record SampleRequest(@NotBlank String name, @Email String email) {
  }

  /** Controller whose only job is to throw what a test asks for. */
  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    RuntimeException exceptionToThrow;

    @GetMapping("/throw")
    String throwConfiguredException() {
      throw exceptionToThrow;
    }

    @GetMapping("/items/{id}")
    String item(@PathVariable Long id) {
      return "ok";
    }

    @PostMapping("/validated")
    String validated(@Valid @RequestBody SampleRequest request) {
      return "ok";
    }
  }
}
