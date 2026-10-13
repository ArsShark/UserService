package com.grechanikovars.userservice.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Payload for creating a payment card or fully replacing an existing one.
 *
 * <p>The owner is never part of the body: it comes from the URL when a card is created and
 * cannot be changed afterwards. The number must contain digits only, so that the uniqueness
 * check is not fooled by formatting such as spaces or dashes.
 */
public record CardRequest(
  @NotBlank @Pattern(regexp = "\\d{13,19}", message = "must contain 13 to 19 digits") String number,
  @NotBlank @Size(max = 100) String holder,
  @NotNull @FutureOrPresent LocalDate expirationDate) {
}
