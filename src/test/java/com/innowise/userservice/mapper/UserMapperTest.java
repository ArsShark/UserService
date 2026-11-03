package com.innowise.userservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.dto.response.UserResponse;
import com.innowise.userservice.dto.response.UserWithCardsResponse;
import com.innowise.userservice.entity.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Tests of the generated {@link UserMapper}; no database is involved. */
@SpringJUnitConfig({UserMapperImpl.class, PaymentCardMapperImpl.class})
class UserMapperTest {

  @Autowired
  private UserMapper mapper;

  @Test
  void toResponseMapsAllFields() {
    User user = MapperTestEntities.user(7L);

    UserResponse response = mapper.toResponse(user);

    assertAll(
      () -> assertEquals(7L, response.id()),
      () -> assertEquals("John", response.name()),
      () -> assertEquals("Smith", response.surname()),
      () -> assertEquals(LocalDate.of(1990, 5, 17), response.birthDate()),
      () -> assertEquals("john.smith@example.com", response.email()),
      () -> assertTrue(response.active()),
      () -> assertEquals(MapperTestEntities.CREATED_AT, response.createdAt()),
      () -> assertEquals(MapperTestEntities.UPDATED_AT, response.updatedAt()));
  }

  @Test
  void toResponseWithCardsMapsCardsWithMaskedNumbers() {
    User user = MapperTestEntities.user(7L);
    MapperTestEntities.card(11L, user, "4000000000000001");
    MapperTestEntities.card(12L, user, "5000000000000002");

    UserWithCardsResponse response = mapper.toResponseWithCards(user);

    assertAll(
      () -> assertEquals(7L, response.id()),
      () -> assertEquals("john.smith@example.com", response.email()),
      () -> assertEquals(2, response.cards().size()),
      () -> assertEquals("************0001", response.cards().get(0).maskedNumber()),
      () -> assertEquals("************0002", response.cards().get(1).maskedNumber()),
      () -> assertEquals(7L, response.cards().get(0).userId()));
  }

  @Test
  void toResponseWithCardsReturnsEmptyListForUserWithoutCards() {
    UserWithCardsResponse response = mapper.toResponseWithCards(MapperTestEntities.user(7L));

    assertTrue(response.cards().isEmpty());
  }

  @Test
  void toEntityCopiesRequestFieldsExceptEmailAndKeepsDefaults() {
    UserRequest request =
      new UserRequest("Jane", "Doe", LocalDate.of(1985, 3, 2), "Jane.Doe@Example.com");

    User user = mapper.toEntity(request);

    assertAll(
      () -> assertNull(user.getId()),
      () -> assertEquals("Jane", user.getName()),
      () -> assertEquals("Doe", user.getSurname()),
      () -> assertEquals(LocalDate.of(1985, 3, 2), user.getBirthDate()),
      () -> assertNull(user.getEmail()),
      () -> assertTrue(user.isActive()),
      () -> assertTrue(user.getCards().isEmpty()));
  }

  @Test
  void updateEntityReplacesRequestFieldsAndKeepsEmailActiveFlagAndCards() {
    User user = MapperTestEntities.user(7L);
    MapperTestEntities.card(11L, user, "4000000000000001");
    user.setActive(false);
    UserRequest request =
      new UserRequest("Jane", "Doe", LocalDate.of(1985, 3, 2), "jane.doe@example.com");

    mapper.updateEntity(request, user);

    assertAll(
      () -> assertEquals(7L, user.getId()),
      () -> assertEquals("Jane", user.getName()),
      () -> assertEquals("Doe", user.getSurname()),
      () -> assertEquals(LocalDate.of(1985, 3, 2), user.getBirthDate()),
      () -> assertEquals("john.smith@example.com", user.getEmail()),
      () -> assertFalse(user.isActive()),
      () -> assertEquals(1, user.getCards().size()));
  }
}
