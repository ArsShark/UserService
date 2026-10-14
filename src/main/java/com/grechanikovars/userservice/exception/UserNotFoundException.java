package com.grechanikovars.userservice.exception;

/** Thrown when a user with the requested id does not exist. */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(Long id) {
    super(String.format("User with id %d not found", id));
  }
}
