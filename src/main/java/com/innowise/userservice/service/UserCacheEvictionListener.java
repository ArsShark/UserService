package com.innowise.userservice.service;

import com.innowise.userservice.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Removes a user from the user cache after the transaction that changed the user has committed.
 *
 * <p>The listener is a separate bean because Spring applies cache annotations only to calls that
 * go through a bean proxy. The event is delivered by the transaction, so the eviction goes
 * through the cache advice and its fail-open error handler like every other cache operation.
 *
 * <p>Why after the commit: a concurrent {@code GET /api/users/{id}} is not blocked by the row
 * lock. If the entry were removed before the commit, such a read could load the old state from
 * the database and cache it again, and it would stay until the TTL ends. After the commit, the
 * database already returns the new state.
 *
 * <p>A rolled-back transaction delivers nothing, so a failed change keeps the entry.
 * {@code fallbackExecution} makes the eviction run at once when there is no transaction.
 */
@Component
public class UserCacheEvictionListener {

  /**
   * Removes the cached user, if present.
   *
   * @param event the user whose cache entry is evicted
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @CacheEvict(cacheNames = CacheConfig.USERS, key = "#event.userId()")
  public void evictAfterCommit(UserCacheEvictionEvent event) {
    // Intentionally empty: the cache advice removes the entry.
  }
}
