package com.grechanikovars.userservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

/** Tests of {@link CacheConfig}: the serializer and the configuration of the users cache. */
class CacheConfigTest {

  private static final Instant CREATED = Instant.parse("2026-10-15T10:15:30.123456Z");

  @Test
  void usersSerializerRestoresResponseWithCards() {
    CardResponse card = new CardResponse(42L, 7L, "************0001", "IVAN PETROV",
      LocalDate.of(2030, 12, 31), true, CREATED, CREATED);
    UserWithCardsResponse original = user(List.of(card));
    JacksonJsonRedisSerializer<UserWithCardsResponse> serializer = CacheConfig.usersSerializer();

    byte[] bytes = serializer.serialize(original);
    UserWithCardsResponse restored = serializer.deserialize(bytes);
    String json = new String(bytes, StandardCharsets.UTF_8);

    assertAll(
      () -> assertEquals(original, restored),
      () -> assertTrue(json.contains("************0001")),
      () -> assertFalse(json.contains("@class")));
  }

  @Test
  void usersSerializerRestoresResponseWithoutCards() {
    UserWithCardsResponse original = user(List.of());
    JacksonJsonRedisSerializer<UserWithCardsResponse> serializer = CacheConfig.usersSerializer();

    UserWithCardsResponse restored = serializer.deserialize(serializer.serialize(original));

    assertEquals(original, restored);
  }

  @Test
  void usersCacheConfigurationUsesTtlPrefixAndRejectsNullValues() {
    RedisCacheConfiguration configuration =
      CacheConfig.usersCacheConfiguration(Duration.ofMinutes(10));

    assertAll(
      () -> assertEquals(Duration.ofMinutes(10),
        configuration.getTtlFunction().getTimeToLive(7L, null)),
      () -> assertEquals("user-service:users::",
        configuration.getKeyPrefixFor(CacheConfig.USERS)),
      () -> assertFalse(configuration.getAllowCacheNullValues()));
  }

  @Test
  void usersCacheConfigurationRejectsNonPositiveTtl() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> CacheConfig.usersCacheConfiguration(Duration.ZERO)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> CacheConfig.usersCacheConfiguration(Duration.ofSeconds(-1))));
  }

  private static UserWithCardsResponse user(List<CardResponse> cards) {
    return new UserWithCardsResponse(7L, "Ivan", "Petrov", LocalDate.of(1990, 5, 17),
      "ivan@example.com", true, CREATED, CREATED.plusSeconds(60), cards);
  }
}
