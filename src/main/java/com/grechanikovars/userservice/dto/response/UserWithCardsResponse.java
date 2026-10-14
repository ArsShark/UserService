package com.grechanikovars.userservice.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * User data together with the user's cards.
 *
 * <p>Returned when a single user is requested by id; this is the object cached in Redis.
 */
public record UserWithCardsResponse(
  Long id,
  String name,
  String surname,
  LocalDate birthDate,
  String email,
  boolean active,
  Instant createdAt,
  Instant updatedAt,
  List<CardResponse> cards) {
}
