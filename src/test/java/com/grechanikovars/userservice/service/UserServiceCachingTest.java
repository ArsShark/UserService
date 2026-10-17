package com.grechanikovars.userservice.service;

import static com.grechanikovars.userservice.service.ServiceTestEntities.user;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grechanikovars.userservice.config.CacheConfig;
import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import com.grechanikovars.userservice.entity.User;
import com.grechanikovars.userservice.exception.UserNotFoundException;
import com.grechanikovars.userservice.mapper.PaymentCardMapperImpl;
import com.grechanikovars.userservice.mapper.UserMapperImpl;
import com.grechanikovars.userservice.repository.UserRepository;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Tests the caching of {@link UserService} through the real cache configuration and an
 * in-memory cache. Transactions are not part of this context; the order of the cache and
 * transaction advice is checked with a database in the integration tests.
 */
@SpringJUnitConfig({CacheConfig.class, UserService.class, UserMapperImpl.class,
  PaymentCardMapperImpl.class, SharedCacheTestConfiguration.class,
  UserServiceCachingTest.TestConfig.class})
@TestPropertySource(properties = "app.cache.users-ttl=10m")
class UserServiceCachingTest {

  private static final Long USER_ID = 7L;
  private static final Long OTHER_USER_ID = 8L;
  private static final UserRequest REQUEST = new UserRequest("Ivan", "Petrov",
    LocalDate.of(1990, 5, 17), "ivan@example.com");

  @Autowired
  private UserService userService;

  @Autowired
  private CacheManager cacheManager;

  @MockitoBean
  private UserRepository userRepository;

  @BeforeEach
  void prepare() {
    Objects.requireNonNull(cacheManager.getCache(CacheConfig.USERS)).clear();
    stubUser(USER_ID);
    stubUser(OTHER_USER_ID);
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
    when(userRepository.updateActiveById(any(), anyBoolean(), any())).thenReturn(1);
  }

  @Test
  void secondReadOfTheSameUserIsServedFromTheCache() {
    UserWithCardsResponse first = userService.getById(USER_ID);
    UserWithCardsResponse second = userService.getById(USER_ID);

    assertAll(
      () -> assertEquals(first, second),
      () -> verify(userRepository, times(1)).findByIdWithCards(USER_ID));
  }

  @Test
  void usersAreCachedSeparately() {
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);

    assertAll(
      () -> verify(userRepository, times(1)).findByIdWithCards(USER_ID),
      () -> verify(userRepository, times(1)).findByIdWithCards(OTHER_USER_ID));
  }

  @Test
  void missingUserIsNotCached() {
    when(userRepository.findByIdWithCards(USER_ID)).thenReturn(Optional.empty());

    assertAll(
      () -> assertThrows(UserNotFoundException.class, () -> userService.getById(USER_ID)),
      () -> assertThrows(UserNotFoundException.class, () -> userService.getById(USER_ID)),
      () -> verify(userRepository, times(2)).findByIdWithCards(USER_ID));
  }

  @ParameterizedTest(name = "{0} evicts only the changed user")
  @MethodSource("changesOfTheUser")
  void changeOfTheUserEvictsItsCacheEntry(String change, Consumer<UserService> action) {
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);

    action.accept(userService);
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);

    assertAll(
      () -> verify(userRepository, times(2)).findByIdWithCards(USER_ID),
      () -> verify(userRepository, times(1)).findByIdWithCards(OTHER_USER_ID));
  }

  @Test
  void failedChangeKeepsTheCacheEntry() {
    userService.getById(USER_ID);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> userService.update(USER_ID, REQUEST));
    userService.getById(USER_ID);

    verify(userRepository, times(1)).findByIdWithCards(USER_ID);
  }

  static Stream<Arguments> changesOfTheUser() {
    Consumer<UserService> update = service -> service.update(USER_ID, REQUEST);
    Consumer<UserService> setActive = service -> service.setActive(USER_ID, false);
    Consumer<UserService> delete = service -> service.delete(USER_ID);
    return Stream.of(
      Arguments.of("update", update),
      Arguments.of("setActive", setActive),
      Arguments.of("delete", delete));
  }

  private void stubUser(Long id) {
    User user = user(id);
    when(userRepository.findByIdWithCards(id)).thenReturn(Optional.of(user));
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
  }

  /** Beans the cache context needs besides the classes under test. */
  @Configuration
  static class TestConfig {

    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager(CacheConfig.USERS);
    }
  }
}
