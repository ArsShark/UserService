package com.innowise.userservice.config;

import com.innowise.userservice.dto.response.UserWithCardsResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enables caching and configures the Redis cache of users.
 *
 * <p>The cache advice is ordered before the transaction advice, so a cache hit does not open a
 * transaction and an eviction runs after the commit of the annotated method. Cache failures
 * never fail a request, see {@link FailOpenCacheErrorHandler}.
 */
@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
public class CacheConfig implements CachingConfigurer {

  /** Name of the cache that holds {@link UserWithCardsResponse} objects, keyed by user id. */
  public static final String USERS = "users";

  private static final String KEY_PREFIX = "user-service:";

  @Override
  public CacheErrorHandler errorHandler() {
    return new FailOpenCacheErrorHandler();
  }

  /**
   * Registers the {@value #USERS} cache in the cache manager built by Spring Boot.
   *
   * <p>Writes are immediate. By default Spring Data Redis sends puts and evictions without
   * waiting for the result, so their failures would never reach the error handler and the next
   * read could still see the old entry. The connection factory is looked up lazily, when the
   * cache manager is built.
   *
   * <p>Other cache names are rejected, so a typo in an annotation fails loudly instead of
   * silently creating a cache with the JDK serialization.
   *
   * @param connectionFactory the Redis connection factory of the application
   * @param usersTtl lifetime of an entry; the safety net for stale data, see the decisions log
   */
  @Bean
  public RedisCacheManagerBuilderCustomizer usersCacheCustomizer(
    ObjectProvider<RedisConnectionFactory> connectionFactory,
    @Value("${app.cache.users-ttl}") Duration usersTtl) {
    return builder -> builder
      .cacheWriter(RedisCacheWriter.create(connectionFactory.getObject(),
        writer -> writer.immediateWrites()))
      .disableCreateOnMissingCache()
      .withCacheConfiguration(USERS, usersCacheConfiguration(usersTtl));
  }

  /**
   * Builds the configuration of the users cache: TTL, key prefix, typed JSON values and no
   * {@code null} values.
   *
   * <p>With the prefix, a key looks like {@code user-service:users::42}.
   */
  static RedisCacheConfiguration usersCacheConfiguration(Duration ttl) {
    Assert.isTrue(ttl.isPositive(), "The TTL of the users cache must be positive");
    return RedisCacheConfiguration.defaultCacheConfig()
      .entryTtl(ttl)
      .prefixCacheNameWith(KEY_PREFIX)
      .disableCachingNullValues()
      .serializeValuesWith(SerializationPair.fromSerializer(usersSerializer()));
  }

  /**
   * Creates the JSON serializer for cached users.
   *
   * <p>It is typed: no {@code @class} metadata is written, so there is no polymorphic
   * deserialization. It uses its own mapper, so the cache format does not depend on the
   * settings of the REST layer.
   */
  static JacksonJsonRedisSerializer<UserWithCardsResponse> usersSerializer() {
    return new JacksonJsonRedisSerializer<>(JsonMapper.builder().build(),
      UserWithCardsResponse.class);
  }
}
