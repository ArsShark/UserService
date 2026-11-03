package com.innowise.userservice.repository.specification;

import com.innowise.userservice.entity.PaymentCard;
import com.innowise.userservice.entity.User;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specifications for filtering {@link PaymentCard} by the name and surname of the owner.
 */
public final class PaymentCardSpecifications {

  private PaymentCardSpecifications() {
  }

  /**
   * Cards whose owner matches both filters ("contains", ignoring case). A blank value means
   * "no restriction on this field". This is one specification, not two composed ones, so the
   * owner is joined once.
   *
   * @param name text to look for in the owner's name, may be {@code null} or blank
   * @param surname text to look for in the owner's surname, may be {@code null} or blank
   * @return the specification
   */
  public static Specification<PaymentCard> ownerMatches(String name, String surname) {
    boolean byName = !SpecificationSupport.isBlank(name);
    boolean bySurname = !SpecificationSupport.isBlank(surname);
    if (!byName && !bySurname) {
      return Specification.unrestricted();
    }
    return (root, query, cb) -> {
      Join<PaymentCard, User> owner = root.join("user");
      List<Predicate> predicates = new ArrayList<>();
      if (byName) {
        predicates.add(SpecificationSupport.containsIgnoreCase(cb, owner.get("name"), name));
      }
      if (bySurname) {
        predicates.add(
          SpecificationSupport.containsIgnoreCase(cb, owner.get("surname"), surname));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }
}
