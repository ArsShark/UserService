package com.innowise.userservice.exception;

import com.innowise.userservice.dto.response.FieldViolation;
import java.util.Comparator;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives the same format for the exceptions
 * raised by Spring MVC itself (malformed JSON, wrong HTTP method, wrong parameter type and so
 * on). This class adds the domain exceptions, request validation failures, the race on a UNIQUE
 * constraint and a last-resort handler.
 *
 * <p>Responses never contain rejected values or internal exception messages, because they may
 * hold an email or a card number.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Name of the UNIQUE constraint on {@code users.email} (Liquibase changelog 001). */
  private static final String UNIQUE_USERS_EMAIL = "uq_users_email";

  /** Name of the UNIQUE constraint on {@code payment_cards.number} (changelog 002). */
  private static final String UNIQUE_CARDS_NUMBER = "uq_payment_cards_number";

  private static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";

  /** The detail of every 401: it never says why, so a missing and a wrong credential look alike. */
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  /** The detail of every 403. */
  private static final String ACCESS_DENIED = "Access is denied";

  /** Maps "resource does not exist" exceptions to 404. */
  @ExceptionHandler({UserNotFoundException.class, PaymentCardNotFoundException.class})
  public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex) {
    return reject(HttpStatus.NOT_FOUND, ex);
  }

  /** Maps business-rule conflicts (duplicates, card limit) to 409. */
  @ExceptionHandler({
    EmailAlreadyExistsException.class,
    CardNumberAlreadyExistsException.class,
    CardLimitExceededException.class})
  public ResponseEntity<ProblemDetail> handleConflict(RuntimeException ex) {
    return reject(HttpStatus.CONFLICT, ex);
  }

  /**
   * Handles database constraint violations.
   *
   * <p>The services check uniqueness before saving, but two parallel requests can both pass the
   * check; then the UNIQUE constraint is the real guard and the violation arrives here. Only
   * the two known unique constraints are a client error (409). Any other violation (NOT NULL,
   * length, foreign key) means a bug on our side and becomes a 500.
   *
   * <p>The exception itself is not logged: its message contains the failing row, including the
   * email or the card number.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
    DataIntegrityViolationException ex) {
    String constraint = findConstraintName(ex);
    if (UNIQUE_USERS_EMAIL.equals(constraint)) {
      return reject(HttpStatus.CONFLICT, new EmailAlreadyExistsException());
    }
    if (UNIQUE_CARDS_NUMBER.equals(constraint)) {
      return reject(HttpStatus.CONFLICT, new CardNumberAlreadyExistsException());
    }
    LOGGER.error("Unexpected data integrity violation, constraint: {}", constraint);
    return internalError();
  }

  /**
   * Handles a caller that is not authenticated: 401 with a constant detail.
   *
   * <p>The exceptions of the filter chain get here through {@code ProblemDetailSecurityHandler};
   * the message of the exception is never sent, because it may describe the token.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    return reject(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, ex);
  }

  /**
   * Handles an authenticated caller that has no right to the resource: 403 with a constant
   * detail. Without this handler the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return reject(HttpStatus.FORBIDDEN, ACCESS_DENIED, ex);
  }

  /** Last resort: logs the exception with its stack trace and hides the details from the client. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    LOGGER.error("Unhandled exception", ex);
    return internalError();
  }
  /** Maps a request to sort by a property that is not part of the API to 400. */
  @ExceptionHandler(InvalidSortPropertyException.class)
  public ResponseEntity<ProblemDetail> handleInvalidSort(InvalidSortPropertyException ex) {
    return reject(HttpStatus.BAD_REQUEST, ex);
  }

  /** Adds the list of invalid fields (without rejected values) to the 400 response. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
    MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
    WebRequest request) {
    List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
      .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
      .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
      .toList();
    ProblemDetail problem =
      ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("errors", violations);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  private static ResponseEntity<ProblemDetail> reject(HttpStatus status, RuntimeException ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(), ex.getMessage());
    return ResponseEntity.status(status)
      .body(ProblemDetail.forStatusAndDetail(status, ex.getMessage()));
  }

  /** A security rejection: the detail is fixed, only the class of the exception is logged. */
  private static ResponseEntity<ProblemDetail> reject(HttpStatus status, String detail,
    Exception ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(),
      ex.getClass().getSimpleName());
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  private static ResponseEntity<ProblemDetail> internalError() {
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
      .body(ProblemDetail.forStatusAndDetail(status, INTERNAL_ERROR_DETAIL));
  }

  /** Returns the violated constraint name from the cause chain, or null if there is none. */
  private static String findConstraintName(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }
}
