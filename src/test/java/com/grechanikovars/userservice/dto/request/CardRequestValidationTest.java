package com.grechanikovars.userservice.dto.request;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the constraints declared on {@link CardRequest}. */
class CardRequestValidationTest extends AbstractDtoValidationTest {

  private static final String NUMBER = "4000000000000001";
  private static final String HOLDER = "JOHN SMITH";
  private static final LocalDate EXPIRATION = LocalDate.now().plusYears(2);

  @Test
  void validRequestHasNoViolations() {
    assertTrue(violatedProperties(new CardRequest(NUMBER, HOLDER, EXPIRATION)).isEmpty());
  }

  @Test
  void numberMustHaveBetween13And19Digits() {
    assertAll(
      () -> assertEquals(Set.of("number"), violatedProperties(withNumber("1".repeat(12)))),
      () -> assertTrue(violatedProperties(withNumber("1".repeat(13))).isEmpty()),
      () -> assertTrue(violatedProperties(withNumber("1".repeat(19))).isEmpty()),
      () -> assertEquals(Set.of("number"), violatedProperties(withNumber("1".repeat(20)))));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "4000 0000 0000 0001", "4000-0000-0000-0001", "40000000000000x1"})
  void numberWithNonDigitsOrBlankIsRejected(String value) {
    assertEquals(Set.of("number"), violatedProperties(withNumber(value)));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void blankHolderIsRejected(String value) {
    assertEquals(Set.of("holder"),
      violatedProperties(new CardRequest(NUMBER, value, EXPIRATION)));
  }

  @Test
  void holderIsLimitedTo100Characters() {
    assertAll(
      () -> assertTrue(
        violatedProperties(new CardRequest(NUMBER, "A".repeat(100), EXPIRATION)).isEmpty()),
      () -> assertEquals(Set.of("holder"),
        violatedProperties(new CardRequest(NUMBER, "A".repeat(101), EXPIRATION))));
  }

  @Test
  void expirationDateMustNotBeInThePast() {
    LocalDate today = LocalDate.now();
    assertAll(
      () -> assertEquals(Set.of("expirationDate"),
        violatedProperties(new CardRequest(NUMBER, HOLDER, null))),
      () -> assertEquals(Set.of("expirationDate"),
        violatedProperties(new CardRequest(NUMBER, HOLDER, today.minusDays(1)))),
      () -> assertTrue(violatedProperties(new CardRequest(NUMBER, HOLDER, today)).isEmpty()),
      () -> assertTrue(
        violatedProperties(new CardRequest(NUMBER, HOLDER, today.plusDays(1))).isEmpty()));
  }

  private static CardRequest withNumber(String number) {
    return new CardRequest(number, HOLDER, EXPIRATION);
  }
}
