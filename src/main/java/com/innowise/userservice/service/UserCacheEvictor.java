package com.innowise.userservice.service;

import com.innowise.userservice.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

/**
 * Evicts a user from the user cache.
 *
 * <p>The cached {@code UserWithCardsResponse} contains the cards, so every change of a card must
 * evict its owner. The eviction lives in its own bean for two reasons: Spring applies cache
 * annotations only to calls that go through a bean proxy, so a method of the same class would
 * be ignored (self-invocation); and one bean gives every card operation the same rule and the
 * same failure handling as the other cache operations.
 */
@Component
public class UserCacheEvictor {

  /**
   * Removes the cached user, if present.
   *
   * @param userId id of the user whose cache entry is evicted
   */
  @CacheEvict(cacheNames = CacheConfig.USERS, key = "#userId")
  public void evict(Long userId) {
    // Intentionally empty: the cache advice removes the entry.
  }
}
