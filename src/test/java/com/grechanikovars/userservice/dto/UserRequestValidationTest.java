package com.grechanikovars.userservice.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.grechanikovars.userservice.dto.request.UserRequest;

/** Verifies the constraints declared on {@link UserRequest}. */
class UserRequestValidationTest extends AbstractDtoValidationTest {

  private static final String NAME = "John";
  private static final String SURNAME = "Smith";
  private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 5, 17);
  private static final String EMAIL = "john.smith@example.com";

  @Test
  void validRequestHasNoViolations() {
    assertTrue(violatedProperties(new UserRequest(NAME, SURNAME, BIRTH_DATE, EMAIL)).isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void blankNameAndSurnameAreRejected(String value) {
    assertAll(
      () -> assertEquals(Set.of("name"),
        violatedProperties(new UserRequest(value, SURNAME, BIRTH_DATE, EMAIL))),
      () -> assertEquals(Set.of("surname"),
        violatedProperties(new UserRequest(NAME, value, BIRTH_DATE, EMAIL))));
  }

  @Test
  void nameAndSurnameAreLimitedTo100Characters() {
    String max = "a".repeat(100);
    String tooLong = "a".repeat(101);
    assertAll(
      () -> assertTrue(violatedProperties(new UserRequest(max, max, BIRTH_DATE, EMAIL)).isEmpty()),
      () -> assertEquals(Set.of("name", "surname"),
        violatedProperties(new UserRequest(tooLong, tooLong, BIRTH_DATE, EMAIL))));
  }

  @Test
  void birthDateMustBeInThePast() {
    LocalDate today = LocalDate.now();
    assertAll(
      () -> assertEquals(Set.of("birthDate"),
        violatedProperties(new UserRequest(NAME, SURNAME, null, EMAIL))),
      () -> assertEquals(Set.of("birthDate"),
        violatedProperties(new UserRequest(NAME, SURNAME, today, EMAIL))),
      () -> assertEquals(Set.of("birthDate"),
        violatedProperties(new UserRequest(NAME, SURNAME, today.plusDays(1), EMAIL))),
      () -> assertTrue(
        violatedProperties(new UserRequest(NAME, SURNAME, today.minusDays(1), EMAIL)).isEmpty()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "plainaddress", "john@", "@example.com"})
  void invalidEmailIsRejected(String value) {
    assertEquals(Set.of("email"),
      violatedProperties(new UserRequest(NAME, SURNAME, BIRTH_DATE, value)));
  }

  @Test
  void everyInvalidFieldIsReported() {
    assertEquals(Set.of("name", "surname", "birthDate", "email"),
      violatedProperties(new UserRequest("", "", null, "not-an-email")));
  }
}
