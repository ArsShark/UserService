package com.grechanikovars.userservice.repository;

import com.grechanikovars.userservice.TestcontainersConfiguration;
import com.grechanikovars.userservice.config.JpaAuditingConfig;
import com.grechanikovars.userservice.config.ClockConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Base class for repository tests: a JPA test slice on a real PostgreSQL container. The auditing
 * and clock configurations are imported explicitly, because test slices do not scan
 * {@code @Configuration} classes. Each test runs in a transaction that is rolled back afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class, ClockConfig.class})
abstract class AbstractRepositoryTest {

  @PersistenceContext
  protected EntityManager entityManager;

  /**
   * Sends pending changes to the database and detaches all entities, so the next read goes to
   * the database instead of the persistence context.
   */
  protected void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
