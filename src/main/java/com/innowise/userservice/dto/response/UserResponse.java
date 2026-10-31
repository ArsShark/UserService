package com.innowise.userservice.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/** User data without cards, used for lists and for the results of write operations. */
public record UserResponse(
  Long id,
  String name,
  String surname,
  LocalDate birthDate,
  String email,
  boolean active,
  Instant createdAt,
  Instant updatedAt) {
}
