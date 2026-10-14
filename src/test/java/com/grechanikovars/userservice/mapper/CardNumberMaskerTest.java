package com.grechanikovars.userservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests of {@link CardNumberMasker}. */
class CardNumberMaskerTest {

  @ParameterizedTest
  @CsvSource({
    "4000000000000001, ************0001",
    "4000000000001, *********0001",
    "1234567890123456789, ***************6789",
    "12345, *2345"
  })
  void keepsOnlyTheLastFourCharacters(String number, String expected) {
    assertEquals(expected, CardNumberMasker.mask(number));
  }

  @Test
  void masksValuesOfFourCharactersOrFewerCompletely() {
    assertAll(
      () -> assertEquals("****", CardNumberMasker.mask("1234")),
      () -> assertEquals("***", CardNumberMasker.mask("123")),
      () -> assertEquals("", CardNumberMasker.mask("")));
  }

  @Test
  void keepsNullAsNull() {
    assertNull(CardNumberMasker.mask(null));
  }
}
