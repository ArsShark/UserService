package com.innowise.userservice.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Asks for a user to be evicted from the user cache.
 *
 * <p>The cached {@code UserWithCardsResponse} contains the cards, so every change of a card must
 * evict its owner. One bean gives every card operation the same rule.
 *
 * <p>The eviction itself does not happen here. This class publishes a
 * {@link UserCacheEvictionEvent}; {@link UserCacheEvictionListener} removes the entry after the
 * transaction of the caller has committed (see the listener for the reason).
 */
@Component
public class UserCacheEvictor {

  private final ApplicationEventPublisher publisher;

  public UserCacheEvictor(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Schedules the removal of the cached user: after the commit of the current transaction, or at
   * once when there is none.
   *
   * @param userId id of the user whose cache entry is evicted
   */
  public void evict(Long userId) {
    publisher.publishEvent(new UserCacheEvictionEvent(userId));
  }
}
