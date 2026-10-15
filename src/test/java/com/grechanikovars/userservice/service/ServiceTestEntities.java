package com.grechanikovars.userservice.service;

import com.grechanikovars.userservice.entity.User;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;

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
}
