package com.grechanikovars.userservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the application clock, so the current time comes from one replaceable source. */
@Configuration
public class ClockConfig {

  /** UTC system clock. Tests may replace it with a fixed clock. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
