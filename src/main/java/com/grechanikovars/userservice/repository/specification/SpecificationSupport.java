package com.grechanikovars.userservice.repository.specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.Locale;

/** Shared helpers for the specifications of this package. */
final class SpecificationSupport {

  private static final char ESCAPE = '\\';

  private SpecificationSupport() {
  }

  /**
   * Checks whether a filter value is absent.
   *
   * @param value filter value, may be {@code null}
   * @return {@code true} if the value is {@code null} or blank
   */
  static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Builds a case-insensitive "contains" predicate. The value is stripped and lowercased, and
   * the LIKE wildcards {@code %} and {@code _} in it are escaped, so they match literally.
   *
   * @param cb criteria builder
   * @param path string expression to compare
   * @param value non-blank filter value
   * @return the predicate {@code lower(path) LIKE '%value%'}
   */
  static Predicate containsIgnoreCase(
    CriteriaBuilder cb, Expression<String> path, String value) {
    String escaped = value.strip().toLowerCase(Locale.ROOT)
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_");
    return cb.like(cb.lower(path), String.format("%%%s%%", escaped), ESCAPE);
  }
}
