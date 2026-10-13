package com.grechanikovars.userservice.repository.specification;

import com.grechanikovars.userservice.entity.User;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specifications for filtering {@link User} by name and surname. A blank filter value means
 * "no restriction". A contains-search cannot use the btree index
 * {@code idx_users_surname_name}; that is acceptable at this scale.
 */
public final class UserSpecifications {

  private UserSpecifications() {
  }

  /**
   * Users whose name contains the given text, ignoring case.
   *
   * @param name text to look for, {@code null} or blank for no restriction
   * @return the specification
   */
  public static Specification<User> nameContains(String name) {
    if (SpecificationSupport.isBlank(name)) {
      return Specification.unrestricted();
    }
    return (root, query, cb) ->
      SpecificationSupport.containsIgnoreCase(cb, root.get("name"), name);
  }

  /**
   * Users whose surname contains the given text, ignoring case.
   *
   * @param surname text to look for, {@code null} or blank for no restriction
   * @return the specification
   */
  public static Specification<User> surnameContains(String surname) {
    if (SpecificationSupport.isBlank(surname)) {
      return Specification.unrestricted();
    }
    return (root, query, cb) ->
      SpecificationSupport.containsIgnoreCase(cb, root.get("surname"), surname);
  }

  /**
   * Users that match both filters. Absent filters are ignored.
   *
   * @param name text to look for in the name, may be {@code null} or blank
   * @param surname text to look for in the surname, may be {@code null} or blank
   * @return the combined specification
   */
  public static Specification<User> matches(String name, String surname) {
    return Specification.allOf(nameContains(name), surnameContains(surname));
  }
}
