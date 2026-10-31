package com.innowise.userservice.mapper;

import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.dto.response.UserResponse;
import com.innowise.userservice.dto.response.UserWithCardsResponse;
import com.innowise.userservice.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between {@link User} and user DTOs.
 *
 * <p>The email is never copied from a request: the service normalizes it (strip and lower-case)
 * to check uniqueness first, and then sets the normalized value itself.
 */
@Mapper(
  componentModel = "spring",
  unmappedTargetPolicy = ReportingPolicy.ERROR,
  uses = PaymentCardMapper.class)
public interface UserMapper {

  /** Maps a user without cards; the cards collection is not touched. */
  UserResponse toResponse(User user);

  /** Maps a user together with its cards; call it only for users whose cards are loaded. */
  UserWithCardsResponse toResponseWithCards(User user);

  /** Creates a new user from a request. A new user is always active and has no cards. */
  @Mapping(target = "email", ignore = true)
  @Mapping(target = "active", ignore = true)
  @Mapping(target = "cards", ignore = true)
  User toEntity(UserRequest request);

  /** Replaces the editable fields of an existing user; email, active flag and cards are kept. */
  @Mapping(target = "email", ignore = true)
  @Mapping(target = "active", ignore = true)
  @Mapping(target = "cards", ignore = true)
  void updateEntity(UserRequest request, @MappingTarget User user);
}
