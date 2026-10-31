package com.innowise.userservice.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Payment card data returned to clients.
 *
 * @param maskedNumber the card number with all but the last four digits replaced by '*',
 *                     for example {@code ************0001}; the full number is never returned
 */
public record CardResponse(
  Long id,
  Long userId,
  String maskedNumber,
  String holder,
  LocalDate expirationDate,
  boolean active,
  Instant createdAt,
  Instant updatedAt) {
}
