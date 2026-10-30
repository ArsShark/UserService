package com.innowise.userservice.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.userservice.entity.PaymentCard;
import com.innowise.userservice.entity.User;
import com.innowise.userservice.repository.specification.PaymentCardSpecifications;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Tests of {@link PaymentCardRepository} against the schema created by Liquibase. */
class PaymentCardRepositoryTest extends AbstractRepositoryTest {

  private static final Instant FIXED_NOW = Instant.parse("2030-01-01T10:15:30Z");

  @Autowired
  private PaymentCardRepository paymentCardRepository;

  @Autowired
  private UserRepository userRepository;

  @Test
  void findAllByUserIdReturnsOnlyOwnCards() {
    User first = userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001", "4000000000000002"));
    userRepository.save(TestEntities.userWithCards(
      "Ivan", "Petrov", "ivan@example.com", "4000000000000003"));
    flushAndClear();

    List<String> numbers = paymentCardRepository.findAllByUserId(first.getId()).stream()
      .map(PaymentCard::getNumber)
      .sorted()
      .toList();

    assertEquals(List.of("4000000000000001", "4000000000000002"), numbers);
  }

  @Test
  void findUserIdByIdReadsTheOwnerOfTheCard() {
    User first = userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001"));
    User second = userRepository.save(TestEntities.userWithCards(
      "Ivan", "Petrov", "ivan@example.com", "4000000000000002"));
    flushAndClear();
    Long firstCard = paymentCardRepository.findAllByUserId(first.getId()).get(0).getId();
    Long secondCard = paymentCardRepository.findAllByUserId(second.getId()).get(0).getId();

    assertAll(
      () -> assertEquals(Optional.of(first.getId()),
        paymentCardRepository.findUserIdById(firstCard)),
      () -> assertEquals(Optional.of(second.getId()),
        paymentCardRepository.findUserIdById(secondCard)),
      () -> assertTrue(paymentCardRepository.findUserIdById(secondCard + 1000).isEmpty()));
  }

  @Test
  void countByUserIdAndExistsByNumber() {
    User user = userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001", "4000000000000002"));

    assertAll(
      () -> assertEquals(2, paymentCardRepository.countByUserId(user.getId())),
      () -> assertTrue(paymentCardRepository.existsByNumber("4000000000000001")),
      () -> assertFalse(paymentCardRepository.existsByNumber("4000000000009999")));
  }

  @Test
  void duplicateNumberViolatesUniqueConstraint() {
    userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001"));
    User other = TestEntities.userWithCards(
      "Ivan", "Petrov", "ivan@example.com", "4000000000000001");

    assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(other));
  }

  @Test
  void updateActiveByIdChangesFlagAndUpdatedAt() {
    // The entity stays managed on purpose: only clearAutomatically makes findById read fresh data.
    User user = userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001"));
    Long cardId = user.getCards().get(0).getId();

    int updated = paymentCardRepository.updateActiveById(cardId, false, FIXED_NOW);
    PaymentCard found = paymentCardRepository.findById(cardId).orElseThrow();

    assertAll(
      () -> assertEquals(1, updated),
      () -> assertFalse(found.isActive()),
      () -> assertEquals(FIXED_NOW, found.getUpdatedAt()));
  }

  @Test
  void updateActiveByIdReturnsZeroForUnknownCard() {
    assertEquals(0, paymentCardRepository.updateActiveById(-1L, false, FIXED_NOW));
  }

  @Test
  void ownerFilterMatchesNameAndSurname() {
    saveCardFixtures();

    Page<PaymentCard> byName = paymentCardRepository.findAll(
      PaymentCardSpecifications.ownerMatches("ANN", null),
      PageRequest.of(0, 10, Sort.by("number")));
    Page<PaymentCard> byBoth = paymentCardRepository.findAll(
      PaymentCardSpecifications.ownerMatches("ann", "smith"),
      PageRequest.of(0, 10, Sort.by("number")));

    assertAll(
      () -> assertEquals(
        List.of("4000000000000001", "4000000000000002", "4000000000000003"), numbers(byName)),
      () -> assertEquals(3, byName.getTotalElements()),
      () -> assertEquals(List.of("4000000000000003"), numbers(byBoth)));
  }

  @Test
  void blankOwnerFilterReturnsAllCardsPaged() {
    saveCardFixtures();

    Page<PaymentCard> page = paymentCardRepository.findAll(
      PaymentCardSpecifications.ownerMatches(" ", null),
      PageRequest.of(0, 3, Sort.by("number")));

    assertAll(
      () -> assertEquals(4, page.getTotalElements()),
      () -> assertEquals(2, page.getTotalPages()),
      () -> assertEquals(3, page.getContent().size()));
  }

  private void saveCardFixtures() {
    userRepository.save(TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001", "4000000000000002"));
    userRepository.save(TestEntities.userWithCards(
      "Hanna", "Smith", "hanna@example.com", "4000000000000003"));
    userRepository.save(TestEntities.userWithCards(
      "Ivan", "Petrov", "ivan@example.com", "4000000000000004"));
  }

  private static List<String> numbers(Page<PaymentCard> page) {
    return page.getContent().stream().map(PaymentCard::getNumber).toList();
  }
}
