package com.innowise.userservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.userservice.dto.request.CardRequest;
import com.innowise.userservice.service.PaymentCardService;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that a change of a card removes the owner from the cache only after the commit.
 *
 * <p>The service joins the transaction that the test opens around it, so the test can look at
 * Redis while the transaction is still open (the eviction must not have happened yet), and
 * again after the commit or the rollback.
 */
class CacheEvictionAfterCommitIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private PaymentCardService paymentCardService;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;
  private long userId;
  private long cardId;

  @BeforeEach
  void prepare() throws Exception {
    transaction = new TransactionTemplate(transactionManager);
    userId = createUser("Ivan", "Petrov", "ivan@example.com");
    cardId = createCard(userId, cardNumber(1));
    mockMvc.perform(get("/api/users/{id}", userId)).andExpect(status().isOk());
    assertTrue(cacheKeys().contains(userCacheKey(userId)), "the entry must be cached first");
  }

  @Test
  void entryIsKeptUntilTheCommitAndRemovedAfterIt() {
    AtomicBoolean cachedInsideTransaction = new AtomicBoolean();

    transaction.executeWithoutResult(status -> {
      paymentCardService.setActive(cardId, false);
      cachedInsideTransaction.set(cacheKeys().contains(userCacheKey(userId)));
    });

    assertAll(
      () -> assertTrue(cachedInsideTransaction.get(),
        "the entry was removed before the commit"),
      () -> assertFalse(cacheKeys().contains(userCacheKey(userId)),
        "the entry survived the commit"));
  }

  @Test
  void createAndDeleteAlsoWaitForTheCommit() {
    AtomicBoolean cachedAfterCreate = new AtomicBoolean();
    AtomicBoolean cachedAfterDelete = new AtomicBoolean();

    transaction.executeWithoutResult(status -> {
      paymentCardService.create(userId, new CardRequest(
        cardNumber(2), "IVAN PETROV", LocalDate.of(2032, 12, 31)));
      cachedAfterCreate.set(cacheKeys().contains(userCacheKey(userId)));
      paymentCardService.delete(cardId);
      cachedAfterDelete.set(cacheKeys().contains(userCacheKey(userId)));
    });

    assertAll(
      () -> assertTrue(cachedAfterCreate.get(), "create evicted before the commit"),
      () -> assertTrue(cachedAfterDelete.get(), "delete evicted before the commit"),
      () -> assertFalse(cacheKeys().contains(userCacheKey(userId)),
        "the entry survived the commit"));
  }

  @Test
  void rolledBackChangeKeepsTheEntry() {
    transaction.executeWithoutResult(status -> {
      paymentCardService.setActive(cardId, false);
      status.setRollbackOnly();
    });

    assertTrue(cacheKeys().contains(userCacheKey(userId)),
      "a rolled-back change must not evict the entry");
  }
}
