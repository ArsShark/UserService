package com.innowise.userservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Tests of {@link EmailNormalizer}. */
class EmailNormalizerTest {

  @Test
  void stripsSpacesAndLowersTheCase() {
    assertAll(
      () -> assertEquals("anna@example.com", EmailNormalizer.normalize("  Anna@Example.COM \t")),
      () -> assertEquals("anna@example.com", EmailNormalizer.normalize("anna@example.com")));
  }

  /** In the Turkish locale the default rules would turn an upper case I into a dotless letter. */
  @Test
  void doesNotDependOnTheDefaultLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      assertEquals("info@example.com", EmailNormalizer.normalize("INFO@EXAMPLE.COM"));
    } finally {
      Locale.setDefault(previous);
    }
  }
}
