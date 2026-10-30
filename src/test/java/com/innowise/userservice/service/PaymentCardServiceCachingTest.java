package com.innowise.userservice.service;

import static com.innowise.userservice.service.ServiceTestEntities.card;
import static com.innowise.userservice.service.ServiceTestEntities.user;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.innowise.userservice.config.CacheConfig;
import com.innowise.userservice.dto.request.CardRequest;
import com.innowise.userservice.entity.PaymentCard;
import com.innowise.userservice.entity.User;
import com.innowise.userservice.exception.PaymentCardNotFoundException;
import com.innowise.userservice.mapper.PaymentCardMapperImpl;
import com.innowise.userservice.mapper.UserMapperImpl;
import com.innowise.userservice.repository.PaymentCardRepository;
import com.innowise.userservice.repository.UserRepository;
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
 * Tests that every change of a card evicts its owner from the user cache, through the real
 * cache configuration and an in-memory cache.
 */
@SpringJUnitConfig({CacheConfig.class, UserService.class, PaymentCardService.class,
  UserCacheEvictor.class, UserMapperImpl.class, PaymentCardMapperImpl.class,
  SharedCacheTestConfiguration.class, PaymentCardServiceCachingTest.TestConfig.class})
@TestPropertySource(properties = "app.cache.users-ttl=10m")
class PaymentCardServiceCachingTest {

  private static final Long USER_ID = 7L;
  private static final Long OTHER_USER_ID = 8L;
  private static final Long CARD_ID = 42L;
  private static final CardRequest REQUEST = new CardRequest("4111111111111111",
    "IVAN PETROV", LocalDate.of(2032, 12, 31));

  @Autowired
  private UserService userService;

  @Autowired
  private PaymentCardService paymentCardService;

  @Autowired
  private CacheManager cacheManager;

  @MockitoBean
  private UserRepository userRepository;

  @MockitoBean
  private PaymentCardRepository paymentCardRepository;

  @BeforeEach
  void prepare() {
    Objects.requireNonNull(cacheManager.getCache(CacheConfig.USERS)).clear();
    User owner = user(USER_ID);
    PaymentCard card = card(CARD_ID, owner, REQUEST.number());
    when(userRepository.findByIdWithCards(USER_ID)).thenReturn(Optional.of(owner));
    when(userRepository.findByIdWithCards(OTHER_USER_ID))
      .thenReturn(Optional.of(user(OTHER_USER_ID)));
    when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(owner));
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(card));
    when(paymentCardRepository.save(any(PaymentCard.class)))
      .thenAnswer(call -> call.getArgument(0));
    when(paymentCardRepository.saveAndFlush(any(PaymentCard.class)))
      .thenAnswer(call -> call.getArgument(0));
    when(paymentCardRepository.updateActiveById(eq(CARD_ID), anyBoolean(), any())).thenReturn(1);
  }

  @ParameterizedTest(name = "{0} evicts only the owner of the card")
  @MethodSource("changesOfTheCard")
  void changeOfTheCardEvictsItsOwner(String change, Consumer<PaymentCardService> action) {
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);

    action.accept(paymentCardService);
    userService.getById(USER_ID);
    userService.getById(OTHER_USER_ID);

    assertAll(
      () -> verify(userRepository, times(2)).findByIdWithCards(USER_ID),
      () -> verify(userRepository, times(1)).findByIdWithCards(OTHER_USER_ID));
  }

  @Test
  void failedChangeKeepsTheCacheEntry() {
    userService.getById(USER_ID);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

    assertThrows(PaymentCardNotFoundException.class,
      () -> paymentCardService.update(CARD_ID, REQUEST));
    userService.getById(USER_ID);

    verify(userRepository, times(1)).findByIdWithCards(USER_ID);
  }

  @Test
  void readingCardsKeepsTheCacheEntry() {
    userService.getById(USER_ID);
    when(userRepository.existsById(USER_ID)).thenReturn(true);

    paymentCardService.getById(CARD_ID);
    paymentCardService.getAllByUserId(USER_ID);
    userService.getById(USER_ID);

    verify(userRepository, times(1)).findByIdWithCards(USER_ID);
  }

  static Stream<Arguments> changesOfTheCard() {
    Consumer<PaymentCardService> create = service -> service.create(USER_ID, REQUEST);
    Consumer<PaymentCardService> update = service -> service.update(CARD_ID, REQUEST);
    Consumer<PaymentCardService> setActive = service -> service.setActive(CARD_ID, false);
    Consumer<PaymentCardService> delete = service -> service.delete(CARD_ID);
    return Stream.of(
      Arguments.of("create", create),
      Arguments.of("update", update),
      Arguments.of("setActive", setActive),
      Arguments.of("delete", delete));
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
