package com.grechanikovars.userservice.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.grechanikovars.userservice.entity.PaymentCard;
import com.grechanikovars.userservice.entity.User;
import com.grechanikovars.userservice.repository.specification.UserSpecifications;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Tests of {@link UserRepository} against the schema created by Liquibase. */
class UserRepositoryTest extends AbstractRepositoryTest {

  private static final Instant FIXED_NOW = Instant.parse("2030-01-01T10:15:30Z");

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private PaymentCardRepository paymentCardRepository;

  @Test
  void saveFillsAuditingTimestamps() {
    User saved = userRepository.save(TestEntities.user("Anna", "Petrova", "anna@example.com"));
    flushAndClear();

    User found = userRepository.findById(saved.getId()).orElseThrow();

    assertAll(
      () -> assertNotNull(found.getCreatedAt()),
      () -> assertNotNull(found.getUpdatedAt()),
      () -> assertTrue(found.isActive()));
  }

  @Test
  void findByEmailAndExistsByEmail() {
    userRepository.save(TestEntities.user("Anna", "Petrova", "anna@example.com"));

    assertAll(
      () -> assertTrue(userRepository.findByEmail("anna@example.com").isPresent()),
      () -> assertTrue(userRepository.existsByEmail("anna@example.com")),
      () -> assertFalse(userRepository.existsByEmail("nobody@example.com")));
  }

  @Test
  void saveCascadesToCards() {
    User user = TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001", "4000000000000002");

    Long userId = userRepository.save(user).getId();
    flushAndClear();

    assertEquals(2, paymentCardRepository.countByUserId(userId));
  }

  @Test
  void deleteRemovesCardsByCascade() {
    User user = TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001", "4000000000000002");
    Long userId = userRepository.save(user).getId();
    flushAndClear();

    userRepository.deleteById(userId);
    flushAndClear();

    assertAll(
      () -> assertFalse(userRepository.existsById(userId)),
      () -> assertEquals(0, paymentCardRepository.countByUserId(userId)));
  }

  @Test
  void removeCardDeletesOrphan() {
    User user = TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001");
    Long userId = userRepository.save(user).getId();
    flushAndClear();

    User loaded = userRepository.findByIdWithCards(userId).orElseThrow();
    PaymentCard card = loaded.getCards().get(0);
    loaded.removeCard(card);
    flushAndClear();

    assertEquals(0, paymentCardRepository.countByUserId(userId));
  }

  @Test
  void findByIdWithCardsInitializesCards() {
    User user = TestEntities.userWithCards(
      "Anna", "Petrova", "anna@example.com", "4000000000000001");
    Long userId = userRepository.save(user).getId();
    flushAndClear();

    User found = userRepository.findByIdWithCards(userId).orElseThrow();

    // Check before touching the collection: any access would initialize it.
    boolean loaded = entityManager.getEntityManagerFactory()
      .getPersistenceUnitUtil()
      .isLoaded(found, "cards");
    assertTrue(loaded);
  }

  @Test
  void findByIdWithCardsReturnsUserWithoutCards() {
    Long userId = userRepository
      .save(TestEntities.user("Anna", "Petrova", "anna@example.com"))
      .getId();
    flushAndClear();

    assertTrue(userRepository.findByIdWithCards(userId).isPresent());
  }

  @Test
  void updateActiveByIdChangesFlagAndUpdatedAt() {
    // The entity stays managed on purpose: only clearAutomatically makes findById read fresh data.
    Long id = userRepository.save(TestEntities.user("Anna", "Petrova", "anna@example.com")).getId();

    int updated = userRepository.updateActiveById(id, false, FIXED_NOW);
    User found = userRepository.findById(id).orElseThrow();

    assertAll(
      () -> assertEquals(1, updated),
      () -> assertFalse(found.isActive()),
      () -> assertEquals(FIXED_NOW, found.getUpdatedAt()));
  }

  @Test
  void updateActiveByIdReturnsZeroForUnknownUser() {
    assertEquals(0, userRepository.updateActiveById(-1L, false, FIXED_NOW));
  }

  @Test
  void filterByNameIgnoresCase() {
    saveFilterFixtures();

    Page<User> page = userRepository.findAll(
      UserSpecifications.matches("ANN", null), PageRequest.of(0, 10, Sort.by("name")));

    assertEquals(List.of("Anna", "Hanna"), names(page));
  }

  @Test
  void filterByNameAndSurnameCombinesWithAnd() {
    saveFilterFixtures();

    Page<User> page = userRepository.findAll(
      UserSpecifications.matches("ann", "petrov"), PageRequest.of(0, 10, Sort.by("name")));

    assertEquals(List.of("Anna"), names(page));
  }

  @Test
  void filterTreatsLikeWildcardsLiterally() {
    saveFilterFixtures();

    assertAll(
      () -> assertTrue(userRepository.findAll(UserSpecifications.matches("%", null)).isEmpty()),
      () -> assertTrue(
        userRepository.findAll(UserSpecifications.matches("_nna", null)).isEmpty()));
  }

  @Test
  void blankFiltersReturnAllUsersPaged() {
    saveFilterFixtures();

    Page<User> page = userRepository.findAll(
      UserSpecifications.matches(null, "  "), PageRequest.of(0, 3, Sort.by("name")));

    assertAll(
      () -> assertEquals(4, page.getTotalElements()),
      () -> assertEquals(2, page.getTotalPages()),
      () -> assertEquals(3, page.getContent().size()));
  }

  private void saveFilterFixtures() {
    userRepository.saveAll(List.of(
      TestEntities.user("Anna", "Petrova", "anna@example.com"),
      TestEntities.user("Hanna", "Smith", "hanna@example.com"),
      TestEntities.user("Ivan", "Petrov", "ivan@example.com"),
      TestEntities.user("Maria", "Ivanova", "maria@example.com")));
  }

  private static List<String> names(Page<User> page) {
    return page.getContent().stream().map(User::getName).toList();
  }
}
