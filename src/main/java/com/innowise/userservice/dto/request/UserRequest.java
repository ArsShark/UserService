package com.innowise.userservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Payload for creating a user or fully replacing an existing one.
 *
 * <p>Size limits mirror the column lengths of the {@code users} table. A new user is always
 * created active; the flag is changed only through the dedicated status operation.
 * The email is normalized (stripped and lower-cased) in the service layer, so only its format
 * is checked here.
 */
public record UserRequest(
  @NotBlank @Size(max = 100) String name,
  @NotBlank @Size(max = 100) String surname,
  @NotNull @Past LocalDate birthDate,
  @NotBlank @Size(max = 255) @Email String email) {
}
