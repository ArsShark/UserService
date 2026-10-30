package com.innowise.userservice.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/users/{id}/status}: the new value of the active flag.
 *
 * <p>The flag is a {@link Boolean} and not a {@code boolean}, so that a body without the field
 * is rejected instead of silently deactivating the user.
 *
 * @param active {@code true} to activate the user, {@code false} to deactivate
 */
public record UserStatusRequest(@NotNull Boolean active) {
}
