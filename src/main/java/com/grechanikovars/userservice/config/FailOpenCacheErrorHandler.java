package com.grechanikovars.userservice.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Logs cache failures and lets the request continue without the cache.
 *
 * <p>The cache is an optimization and the database stays the source of truth, so a failed
 * read, write or eviction must not fail the request. A failed eviction can leave a stale entry;
 * the TTL of the cache limits how long it lives. Only the exception class is logged, never its
 * message or the cached value: messages of serialization errors may contain an email.
 */
public class FailOpenCacheErrorHandler implements CacheErrorHandler {

  private static final Logger LOGGER = LogManager.getLogger(FailOpenCacheErrorHandler.class);

  @Override
  public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
    log("read", exception, cache, key);
  }

  @Override
  public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                                  Object value) {
    log("write", exception, cache, key);
  }

  @Override
  public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
    log("eviction", exception, cache, key);
  }

  @Override
  public void handleCacheClearError(RuntimeException exception, Cache cache) {
    LOGGER.warn("Cache '{}' clear failed: {}", cache.getName(),
      exception.getClass().getSimpleName());
  }

  private void log(String operation, RuntimeException exception, Cache cache, Object key) {
    LOGGER.warn("Cache '{}' {} failed for key {}: {}", cache.getName(), operation, key,
      exception.getClass().getSimpleName());
  }
}
