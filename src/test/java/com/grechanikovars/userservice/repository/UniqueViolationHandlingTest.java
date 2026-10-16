package com.grechanikovars.userservice.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.grechanikovars.userservice.entity.User;
import com.grechanikovars.userservice.exception.CardNumberAlreadyExistsException;
import com.grechanikovars.userservice.exception.EmailAlreadyExistsException;
import com.grechanikovars.userservice.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Checks that real PostgreSQL unique violations reach {@link GlobalExceptionHandler} in a form
 * it recognizes.
 *
 * <p>The handler's own tests use hand-built exceptions. This class proves that Hibernate reports
 * the constraint name for real violations, independently of the language of the server messages.
 */
class UniqueViolationHandlingTest extends AbstractRepositoryTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Autowired
  private UserRepository userRepository;

  @Test
  void duplicateEmailIsMappedTo409() {
    userRepository.save(TestEntities.user("Anna", "Petrova", "anna@example.com"));
    User duplicate = TestEntities.user("Ivan", "Petrov", "anna@example.com");

    ResponseEntity<ProblemDetail> response = translate(duplicate);

    assertAll(
      () -> assertEquals(409, response.getStatusCode().value()),
      () -> assertEquals(new EmailAlreadyExistsException().getMessage(),
        response.getBody().getDetail()));
  }

  @Test
  void duplicateCardNumberIsMappedTo409() {
    userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001"));
    User duplicate = TestEntities.userWithCards(
      "Ivan", "Petrov", "ivan@example.com", "4000000000000001");

    ResponseEntity<ProblemDetail> response = translate(duplicate);

    assertAll(
      () -> assertEquals(409, response.getStatusCode().value()),
      () -> assertEquals(new CardNumberAlreadyExistsException().getMessage(),
        response.getBody().getDetail()));
  }

  /** Saves the user, expects the database to reject it and passes the violation to the handler. */
  private ResponseEntity<ProblemDetail> translate(User user) {
    DataIntegrityViolationException violation = assertThrows(
      DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(user));
    return handler.handleDataIntegrityViolation(violation);
  }
}
