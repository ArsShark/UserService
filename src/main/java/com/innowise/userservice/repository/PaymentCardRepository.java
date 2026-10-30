package com.innowise.userservice.repository;

import com.innowise.userservice.entity.PaymentCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data access for {@link PaymentCard}: the inherited CRUD plus derived queries by owner and
 * number, a native bulk update of the active flag, and filtered pagination with specifications.
 */
public interface PaymentCardRepository extends JpaRepository<PaymentCard, Long>, JpaSpecificationExecutor<PaymentCard> {

  /**
   * Finds all cards of a user. {@code UserId} is resolved to the {@code user.id} path.
   *
   * @param userId owner id
   * @return the cards of the user, empty if there are none
   */
  List<PaymentCard> findAllByUserId(Long userId);

  /**
   * Reads the id of the owner of a card without loading the card or its owner. The access
   * checks use it to see whether a card belongs to the caller.
   *
   * @param id card id
   * @return the id of the owner, or an empty optional if there is no such card
   */
  @Query("SELECT c.user.id FROM PaymentCard c WHERE c.id = :id")
  Optional<Long> findUserIdById(@Param("id") Long id);

  /**
   * Counts the cards of a user. Used by the service layer to enforce the limit of 5 cards.
   *
   * @param userId owner id
   * @return number of cards of the user
   */
  long countByUserId(Long userId);

  /**
   * Checks whether a card with the given number exists.
   *
   * @param number card number
   * @return {@code true} if such a card exists
   */
  boolean existsByNumber(String number);

  /**
   * Sets the active flag with a native bulk update. The same rules apply as for
   * {@link UserRepository#updateActiveById}: {@code updated_at} is set explicitly and the
   * method must run inside a transaction.
   *
   * @param id card id
   * @param active new value of the flag
   * @param now value for {@code updated_at}, taken from the same clock as JPA Auditing
   * @return number of updated rows, 0 if the card does not exist
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
    value = "UPDATE payment_cards SET active = :active, updated_at = :now WHERE id = :id",
    nativeQuery = true)
  int updateActiveById(
    @Param("id") Long id, @Param("active") boolean active, @Param("now") Instant now);
}
