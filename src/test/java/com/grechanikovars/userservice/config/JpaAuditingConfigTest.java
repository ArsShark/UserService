package com.grechanikovars.userservice.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests of {@link JpaAuditingConfig}. */
class JpaAuditingConfigTest {

  @Test
  void dateTimeProviderReturnsTheInstantOfTheClock() {
    Instant fixed = Instant.parse("2030-01-01T10:15:30Z");
    JpaAuditingConfig config = new JpaAuditingConfig();

    var provider = config.auditingDateTimeProvider(Clock.fixed(fixed, ZoneOffset.UTC));

    assertEquals(Optional.of(fixed), provider.getNow());
  }
}
