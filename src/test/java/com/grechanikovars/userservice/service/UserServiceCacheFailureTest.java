package com.grechanikovars.userservice.service;

import static com.grechanikovars.userservice.service.ServiceTestEntities.user;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grechanikovars.userservice.config.CacheConfig;
import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.UserResponse;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import com.grechanikovars.userservice.entity.User;
import com.grechanikovars.userservice.mapper.PaymentCardMapperImpl;
import com.grechanikovars.userservice.mapper.UserMapperImpl;
import com.grechanikovars.userservice.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Tests that a failing cache never fails {@link UserService}: the cache is fail-open. */
@SpringJUnitConfig({CacheConfig.class, UserService.class, UserMapperImpl.class,
  PaymentCardMapperImpl.class, UserServiceCacheFailureTest.TestConfig.class})
@TestPropertySource(properties = "app.cache.users-ttl=10m")
class UserServiceCacheFailureTest {

  private static final Long USER_ID = 7L;
  private static final UserRequest REQUEST = new UserRequest("Ivan", "Petrov",
    LocalDate.of(1990, 5, 17), "ivan@example.com");

  @Autowired
  private UserService userService;

  @MockitoBean
  private UserRepository userRepository;

  @BeforeEach
  void prepare() {
    User user = user(USER_ID);
    when(userRepository.findByIdWithCards(USER_ID)).thenReturn(Optional.of(user));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
  }

  @Test
  void readFallsBackToTheDatabaseWhenTheCacheFails() {
    UserWithCardsResponse response = userService.getById(USER_ID);

    assertAll(
      () -> assertEquals(USER_ID, response.id()),
      () -> verify(userRepository).findByIdWithCards(USER_ID));
  }

  @Test
  void updateSucceedsWhenTheEvictionFails() {
    UserResponse response = userService.update(USER_ID, REQUEST);

    assertEquals(USER_ID, response.id());
  }

  /** A cache that is down: it knows its name and throws on every other call. */
  @Configuration
  static class TestConfig {

    @Bean
    CacheManager cacheManager() {
      Cache brokenCache = mock(Cache.class, invocation -> {
        if ("getName".equals(invocation.getMethod().getName())) {
          return CacheConfig.USERS;
        }
        throw new IllegalStateException("Redis is down");
      });
      SimpleCacheManager manager = new SimpleCacheManager();
      manager.setCaches(List.of(brokenCache));
      return manager;
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-10-15T10:15:30Z"), ZoneOffset.UTC);
    }

    @Bean
    ConversionService conversionService() {
      return ApplicationConversionService.getSharedInstance();
    }
  }
}
