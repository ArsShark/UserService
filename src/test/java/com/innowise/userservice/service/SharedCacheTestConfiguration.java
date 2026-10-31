package com.innowise.userservice.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;

/**
 * Beans that the cache wiring tests of the services share and that have nothing to do with the
 * cache itself: a fixed clock and a conversion service. Every test adds its own
 * {@code CacheManager}.
 *
 * <p>It must be a {@code @TestConfiguration}: a plain {@code @Configuration} in the test tree
 * would be found by the component scan of the {@code @SpringBootTest} contexts and would clash
 * with the beans of the application.
 */
@TestConfiguration(proxyBeanMethods = false)
class SharedCacheTestConfiguration {

  @Bean
  Clock clock() {
    return Clock.fixed(Instant.parse("2026-10-15T10:15:30Z"), ZoneOffset.UTC);
  }

  /** Lets {@code @Value} convert {@code 10m} to a Duration outside of a Spring Boot context. */
  @Bean
  ConversionService conversionService() {
    return ApplicationConversionService.getSharedInstance();
  }
}
