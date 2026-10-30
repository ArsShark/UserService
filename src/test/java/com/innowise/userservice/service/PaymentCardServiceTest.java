package com.innowise.userservice.service;

import static com.innowise.userservice.service.ServiceTestEntities.card;
import static com.innowise.userservice.service.ServiceTestEntities.user;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.userservice.dto.request.CardRequest;
import com.innowise.userservice.dto.response.CardResponse;
import com.innowise.userservice.entity.PaymentCard;
import com.innowise.userservice.entity.User;
import com.innowise.userservice.exception.CardLimitExceededException;
import com.innowise.userservice.exception.CardNumberAlreadyExistsException;
import com.innowise.userservice.exception.PaymentCardNotFoundException;
import com.innowise.userservice.exception.UserNotFoundException;
import com.innowise.userservice.mapper.PaymentCardMapper;
import com.innowise.userservice.repository.PaymentCardRepository;
import com.innowise.userservice.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests of {@link PaymentCardService} with plain Mockito: no Spring context, no database.
 *
 * <p>The repositories, the mapper and the cache evictor are mocks. The card id (42) and the owner
 * id (7) are different on purpose, so passing one where the other is expected fails a test.
 * Locking and transactions need a real database and are covered by the integration tests.
 * Mockito runs with strict stubs, so a stubbing that the service never uses fails the test.
 */
@ExtendWith(MockitoExtension.class)
class PaymentCardServiceTest {

  private static final Long USER_ID = 7L;
  private static final Long CARD_ID = 42L;
  private static final String NUMBER = "4111111111111111";
  private static final String OTHER_NUMBER = "5500000000000004";
  private static final LocalDate EXPIRATION = LocalDate.of(2032, 12, 31);
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

  @Mock
  private PaymentCardRepository paymentCardRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private PaymentCardMapper paymentCardMapper;

  @Mock
  private UserCacheEvictor userCacheEvictor;

  private PaymentCardService paymentCardService;

  @BeforeEach
  void createService() {
    // Not @InjectMocks: the Clock is not a mock, and Mockito would pass null for it.
    paymentCardService = new PaymentCardService(paymentCardRepository, userRepository,
      paymentCardMapper, Clock.fixed(NOW, ZoneOffset.UTC), userCacheEvictor);
  }

  @Test
  void createLocksOwnerChecksLimitAndSavesCard() {
    User owner = user(USER_ID);
    CardRequest request = request(NUMBER);
    PaymentCard mapped = detachedCard(null);
    PaymentCard saved = detachedCard(CARD_ID);
    CardResponse expected = response(CARD_ID);
    when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(owner));
    // Four cards is the highest count that still allows one more.
    when(paymentCardRepository.countByUserId(USER_ID)).thenReturn(4L);
    when(paymentCardRepository.existsByNumber(NUMBER)).thenReturn(false);
    when(paymentCardMapper.toEntity(request)).thenReturn(mapped);
    when(paymentCardRepository.save(mapped)).thenReturn(saved);
    when(paymentCardMapper.toResponse(saved)).thenReturn(expected);

    CardResponse actual = paymentCardService.create(USER_ID, request);

    InOrder order = inOrder(userRepository, paymentCardRepository, userCacheEvictor);
    assertAll(
      () -> assertSame(expected, actual),
      () -> assertSame(owner, mapped.getUser()),
      () -> assertTrue(owner.getCards().contains(mapped)),
      // The lock has to come before the count, otherwise two requests can both pass the limit.
      () -> order.verify(userRepository).findByIdForUpdate(USER_ID),
      () -> order.verify(paymentCardRepository).countByUserId(USER_ID),
      () -> order.verify(paymentCardRepository).save(mapped),
      () -> order.verify(userCacheEvictor).evict(USER_ID));
  }

  @ParameterizedTest
  @ValueSource(longs = {5, 6})
  void createRejectsUserWithMaximumNumberOfCards(long cardCount) {
    when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
    when(paymentCardRepository.countByUserId(USER_ID)).thenReturn(cardCount);

    assertThrows(CardLimitExceededException.class,
      () -> paymentCardService.create(USER_ID, request(NUMBER)));

    assertAll(
      () -> verify(paymentCardRepository, never()).save(any(PaymentCard.class)),
      () -> verifyNoInteractions(paymentCardMapper, userCacheEvictor));
  }

  @Test
  void createRejectsUnknownOwner() {
    when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class,
      () -> paymentCardService.create(USER_ID, request(NUMBER)));

    verifyNoInteractions(paymentCardRepository, paymentCardMapper, userCacheEvictor);
  }

  @Test
  void createRejectsNumberThatIsAlreadyRegistered() {
    when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
    when(paymentCardRepository.countByUserId(USER_ID)).thenReturn(2L);
    when(paymentCardRepository.existsByNumber(NUMBER)).thenReturn(true);

    assertThrows(CardNumberAlreadyExistsException.class,
      () -> paymentCardService.create(USER_ID, request(NUMBER)));

    assertAll(
      () -> verify(paymentCardRepository, never()).save(any(PaymentCard.class)),
      () -> verifyNoInteractions(paymentCardMapper, userCacheEvictor));
  }

  @Test
  void getByIdReturnsMappedCard() {
    PaymentCard found = card(CARD_ID, user(USER_ID), NUMBER);
    CardResponse expected = response(CARD_ID);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(found));
    when(paymentCardMapper.toResponse(found)).thenReturn(expected);

    assertSame(expected, paymentCardService.getById(CARD_ID));
  }

  @Test
  void getByIdRejectsUnknownCard() {
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

    assertThrows(PaymentCardNotFoundException.class, () -> paymentCardService.getById(CARD_ID));

    verifyNoInteractions(paymentCardMapper);
  }

  @Test
  void getAllMapsEveryElementAndKeepsPageMetadata() {
    Pageable pageable = PageRequest.of(1, 2, Sort.by("holder"));
    User owner = user(USER_ID);
    PaymentCard first = card(1L, owner, NUMBER);
    PaymentCard second = card(2L, owner, OTHER_NUMBER);
    CardResponse firstResponse = response(1L);
    CardResponse secondResponse = response(2L);
    // The content of the specification is covered by the repository tests, so any() is enough.
    when(paymentCardRepository.findAll(
      ArgumentMatchers.<Specification<PaymentCard>>any(), eq(pageable)))
      .thenReturn(new PageImpl<>(List.of(first, second), pageable, 5));
    when(paymentCardMapper.toResponse(first)).thenReturn(firstResponse);
    when(paymentCardMapper.toResponse(second)).thenReturn(secondResponse);

    Page<CardResponse> actual = paymentCardService.getAll("iva", "pet", pageable);

    assertAll(
      () -> assertEquals(List.of(firstResponse, secondResponse), actual.getContent()),
      () -> assertEquals(5, actual.getTotalElements()),
      () -> assertEquals(1, actual.getNumber()),
      () -> assertEquals(2, actual.getSize()));
  }

  @Test
  void getAllByUserIdMapsTheCardsOfTheUserInOrder() {
    User owner = user(USER_ID);
    PaymentCard first = card(1L, owner, NUMBER);
    PaymentCard second = card(2L, owner, OTHER_NUMBER);
    CardResponse firstResponse = response(1L);
    CardResponse secondResponse = response(2L);
    when(userRepository.existsById(USER_ID)).thenReturn(true);
    when(paymentCardRepository.findAllByUserId(USER_ID)).thenReturn(List.of(first, second));
    when(paymentCardMapper.toResponse(first)).thenReturn(firstResponse);
    when(paymentCardMapper.toResponse(second)).thenReturn(secondResponse);

    assertEquals(List.of(firstResponse, secondResponse),
      paymentCardService.getAllByUserId(USER_ID));
  }

  @Test
  void getAllByUserIdReturnsEmptyListForUserWithoutCards() {
    when(userRepository.existsById(USER_ID)).thenReturn(true);
    when(paymentCardRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

    assertTrue(paymentCardService.getAllByUserId(USER_ID).isEmpty());
  }

  @Test
  void getAllByUserIdRejectsUnknownUser() {
    when(userRepository.existsById(USER_ID)).thenReturn(false);

    assertThrows(UserNotFoundException.class, () -> paymentCardService.getAllByUserId(USER_ID));

    verifyNoInteractions(paymentCardRepository);
  }

  @Test
  void updateSkipsUniquenessCheckWhenNumberIsUnchanged() {
    PaymentCard existing = card(CARD_ID, user(USER_ID), NUMBER);
    CardRequest request = request(NUMBER);
    CardResponse expected = response(CARD_ID);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(existing));
    when(paymentCardRepository.saveAndFlush(existing)).thenReturn(existing);
    when(paymentCardMapper.toResponse(existing)).thenReturn(expected);

    CardResponse actual = paymentCardService.update(CARD_ID, request);

    assertAll(
      () -> assertSame(expected, actual),
      () -> verify(paymentCardRepository, never()).existsByNumber(any()),
      () -> verify(paymentCardMapper).updateEntity(request, existing),
      // The owner id comes from the response (7), not from the card id (42).
      () -> verify(userCacheEvictor).evict(USER_ID));
  }

  @Test
  void updateAcceptsNewFreeNumber() {
    PaymentCard existing = card(CARD_ID, user(USER_ID), NUMBER);
    CardRequest request = request(OTHER_NUMBER);
    CardResponse expected = response(CARD_ID);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(existing));
    when(paymentCardRepository.existsByNumber(OTHER_NUMBER)).thenReturn(false);
    when(paymentCardRepository.saveAndFlush(existing)).thenReturn(existing);
    when(paymentCardMapper.toResponse(existing)).thenReturn(expected);

    CardResponse actual = paymentCardService.update(CARD_ID, request);

    assertAll(
      () -> assertSame(expected, actual),
      () -> verify(paymentCardMapper).updateEntity(request, existing),
      () -> verify(userCacheEvictor).evict(USER_ID));
  }

  @Test
  void updateRejectsNumberOfAnotherCard() {
    PaymentCard existing = card(CARD_ID, user(USER_ID), NUMBER);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(existing));
    when(paymentCardRepository.existsByNumber(OTHER_NUMBER)).thenReturn(true);

    assertThrows(CardNumberAlreadyExistsException.class,
      () -> paymentCardService.update(CARD_ID, request(OTHER_NUMBER)));

    assertAll(
      () -> assertEquals(NUMBER, existing.getNumber()),
      () -> verify(paymentCardMapper, never())
        .updateEntity(any(CardRequest.class), any(PaymentCard.class)),
      () -> verify(paymentCardRepository, never()).saveAndFlush(any(PaymentCard.class)),
      () -> verifyNoInteractions(userCacheEvictor));
  }

  @Test
  void updateRejectsUnknownCard() {
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

    assertThrows(PaymentCardNotFoundException.class,
      () -> paymentCardService.update(CARD_ID, request(NUMBER)));

    assertAll(
      () -> verify(paymentCardRepository, never()).saveAndFlush(any(PaymentCard.class)),
      () -> verifyNoInteractions(userCacheEvictor));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void setActiveUpdatesTheFlagReadsTheCardAndEvictsItsOwner(boolean active) {
    PaymentCard reloaded = card(CARD_ID, user(USER_ID), NUMBER);
    CardResponse expected = response(CARD_ID);
    when(paymentCardRepository.updateActiveById(CARD_ID, active, NOW)).thenReturn(1);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(reloaded));
    when(paymentCardMapper.toResponse(reloaded)).thenReturn(expected);

    CardResponse actual = paymentCardService.setActive(CARD_ID, active);

    // The bulk update bypasses the persistence context, so the read has to come after it.
    InOrder order = inOrder(paymentCardRepository, userCacheEvictor);
    assertAll(
      () -> assertSame(expected, actual),
      () -> order.verify(paymentCardRepository).updateActiveById(CARD_ID, active, NOW),
      () -> order.verify(paymentCardRepository).findById(CARD_ID),
      () -> order.verify(userCacheEvictor).evict(USER_ID));
  }

  @Test
  void setActiveRejectsUnknownCard() {
    when(paymentCardRepository.updateActiveById(CARD_ID, true, NOW)).thenReturn(0);

    assertThrows(PaymentCardNotFoundException.class,
      () -> paymentCardService.setActive(CARD_ID, true));

    assertAll(
      () -> verify(paymentCardRepository, never()).findById(any()),
      () -> verifyNoInteractions(paymentCardMapper, userCacheEvictor));
  }

  @Test
  void setActiveRejectsCardDeletedAfterTheUpdate() {
    when(paymentCardRepository.updateActiveById(CARD_ID, false, NOW)).thenReturn(1);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

    assertThrows(PaymentCardNotFoundException.class,
      () -> paymentCardService.setActive(CARD_ID, false));

    verifyNoInteractions(paymentCardMapper, userCacheEvictor);
  }

  @Test
  void deleteRemovesTheCardAndEvictsItsOwner() {
    PaymentCard existing = card(CARD_ID, user(USER_ID), NUMBER);
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.of(existing));

    paymentCardService.delete(CARD_ID);

    InOrder order = inOrder(paymentCardRepository, userCacheEvictor);
    assertAll(
      () -> order.verify(paymentCardRepository).delete(existing),
      () -> order.verify(userCacheEvictor).evict(USER_ID),
      () -> verify(paymentCardRepository, never()).deleteById(any()));
  }

  @Test
  void deleteRejectsUnknownCard() {
    when(paymentCardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

    assertThrows(PaymentCardNotFoundException.class, () -> paymentCardService.delete(CARD_ID));

    assertAll(
      () -> verify(paymentCardRepository, never()).delete(any(PaymentCard.class)),
      () -> verifyNoInteractions(userCacheEvictor));
  }

  /** Builds a card that does not belong to any user yet, like the one the mapper returns. */
  private static PaymentCard detachedCard(Long id) {
    PaymentCard detached = new PaymentCard();
    ReflectionTestUtils.setField(detached, "id", id);
    return detached;
  }

  private static CardRequest request(String number) {
    return new CardRequest(number, "IVAN PETROV", EXPIRATION);
  }

  private static CardResponse response(Long id) {
    return new CardResponse(id, USER_ID, "************1111", "IVAN PETROV", EXPIRATION, true,
      NOW, NOW);
  }
}
