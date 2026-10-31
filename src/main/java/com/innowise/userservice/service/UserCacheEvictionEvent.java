package com.innowise.userservice.service;

/**
 * Says that the cache entry of a user is stale and must be removed once the change that made it
 * stale is committed.
 *
 * @param userId id of the user whose cache entry is evicted
 */
public record UserCacheEvictionEvent(Long userId) {
}
