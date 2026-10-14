package com.grechanikovars.userservice.mapper;

import com.grechanikovars.userservice.entity.PaymentCard;
import com.grechanikovars.userservice.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds entities for mapper tests, including fields that have no public setters. */
final class MapperTestEntities {

  static final Instant CREATED_AT = Instant.parse("2030-01-01T10:00:00Z");
  static final Instant UPDATED_AT = Instant.parse("2030-01-02T11:30:00Z");

  private MapperTestEntities() {
  }

  /** Builds a user with the given id and audit timestamps. */
  static User user(Long id) {
    User user = new User();
    user.setName("John");
    user.setSurname("Smith");
    user.setBirthDate(LocalDate.of(1990, 5, 17));
    user.setEmail("john.smith@example.com");
    assignGeneratedFields(user, id);
    return user;
  }

  /** Builds a card attached to the owner, with the given id and audit timestamps. */
  static PaymentCard card(Long id, User owner, String number) {
    PaymentCard card = new PaymentCard();
    card.setNumber(number);
    card.setHolder("JOHN SMITH");
    card.setExpirationDate(LocalDate.of(2032, 12, 31));
    owner.addCard(card);
    assignGeneratedFields(card, id);
    return card;
  }

  private static void assignGeneratedFields(Object entity, Long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    ReflectionTestUtils.setField(entity, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(entity, "updatedAt", UPDATED_AT);
  }
}
