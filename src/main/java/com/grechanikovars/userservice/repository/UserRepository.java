package com.grechanikovars.userservice.repository;

import com.grechanikovars.userservice.entity.User;
import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/**
 * Data access for {@link User}: the inherited CRUD plus a lookup by email, a JPQL query that
 * loads a user together with the cards, a locking lookup, a bulk update of the active flag
 * and filtered pagination with specifications.
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

  /**
   * Finds a user by email. Emails are stored lowercase, so the caller passes a normalized value.
   *
   * @param email normalized (lowercase) email
   * @return the user, or an empty optional if there is none
   */
  Optional<User> findByEmail(String email);

  /**
   * Checks whether a user with the given normalized (lowercase) email exists.
   *
   * @param email normalized (lowercase) email
   * @return {@code true} if such a user exists
   */
  boolean existsByEmail(String email);

  /**
   * Finds a user together with the cards in a single query. {@code LEFT JOIN} keeps users
   * without cards. Do not combine this query with pagination: Hibernate would paginate in memory.
   *
   * @param id user id
   * @return the user with initialized cards, or an empty optional
   */
  @Query("SELECT u FROM User u LEFT JOIN FETCH u.cards WHERE u.id = :id")
  Optional<User> findByIdWithCards(@Param("id") Long id);

  /**
   * Finds a user and takes a pessimistic write lock on its row ({@code SELECT ... FOR UPDATE}
   * or the dialect's equivalent). Other transactions that lock the same user wait until the
   * current one ends, which serializes operations that check a per-user invariant, such as the
   * card limit. Must run inside a transaction: the lock is held until it ends.
   *
   * @param id user id
   * @return the locked user, or an empty optional
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);

  /**
   * Sets the active flag with a JPQL bulk update. A bulk update bypasses JPA Auditing, so
   * {@code updated_at} is set explicitly. Must run inside a transaction.
   *
   * @param id user id
   * @param active new value of the flag
   * @param now value for {@code updated_at}, taken from the same clock as JPA Auditing
   * @return number of updated rows, 0 if the user does not exist
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE User u SET u.active = :active, u.updatedAt = :now WHERE u.id = :id")
  int updateActiveById(
    @Param("id") Long id, @Param("active") boolean active, @Param("now") Instant now);
}
