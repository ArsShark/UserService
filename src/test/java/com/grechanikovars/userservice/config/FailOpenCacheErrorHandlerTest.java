package com.grechanikovars.userservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

/** Tests of {@link FailOpenCacheErrorHandler}. */
class FailOpenCacheErrorHandlerTest {

  private final FailOpenCacheErrorHandler handler = new FailOpenCacheErrorHandler();

  @Test
  void swallowsEveryKindOfCacheError() {
    Cache cache = mock(Cache.class);
    when(cache.getName()).thenReturn(CacheConfig.USERS);
    RuntimeException failure = new IllegalStateException("Redis is down");

    assertAll(
      () -> assertDoesNotThrow(() -> handler.handleCacheGetError(failure, cache, 7L)),
      () -> assertDoesNotThrow(() -> handler.handleCachePutError(failure, cache, 7L, "value")),
      () -> assertDoesNotThrow(() -> handler.handleCacheEvictError(failure, cache, 7L)),
      () -> assertDoesNotThrow(() -> handler.handleCacheClearError(failure, cache)));
  }
}
