package com.grechanikovars.userservice.service;

import com.grechanikovars.userservice.entity.User;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;
import com.grechanikovars.userservice.entity.PaymentCard;

/** Builds entities for service tests, including the id that has no public setter. */
final class ServiceTestEntities {

  private ServiceTestEntities() {
  }

  /** Builds a user with the given id and no cards. */
  static User user(Long id) {
    User user = new User();
    user.setName("Ivan");
    user.setSurname("Petrov");
    user.setBirthDate(LocalDate.of(1990, 5, 17));
    user.setEmail("ivan@example.com");
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  /** Builds a card attached to the owner, with the given id. */
  static PaymentCard card(Long id, User owner, String number) {
    PaymentCard card = new PaymentCard();
    card.setNumber(number);
    card.setHolder("IVAN PETROV");
    card.setExpirationDate(LocalDate.of(2032, 12, 31));
    owner.addCard(card);
    ReflectionTestUtils.setField(card, "id", id);
    return card;
  }

}
