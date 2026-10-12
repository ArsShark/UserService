package com.grechanikovars.userservice.repository;

import com.grechanikovars.userservice.entity.PaymentCard;
import com.grechanikovars.userservice.entity.User;
import java.time.LocalDate;

/** Factory methods for valid transient entities used in repository tests. */
final class TestEntities {

  private TestEntities() {
  }

  static User user(String name, String surname, String email) {
    User user = new User();
    user.setName(name);
    user.setSurname(surname);
    user.setBirthDate(LocalDate.of(1990, 1, 1));
    user.setEmail(email);
    return user;
  }

  static PaymentCard card(String number) {
    PaymentCard card = new PaymentCard();
    card.setNumber(number);
    card.setHolder("TEST HOLDER");
    card.setExpirationDate(LocalDate.of(2030, 12, 31));
    return card;
  }

  static User userWithCards(String name, String surname, String email, String... numbers) {
    User user = user(name, surname, email);
    for (String number : numbers) {
      user.addCard(card(number));
    }
    return user;
  }
}
