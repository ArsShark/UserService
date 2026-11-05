package com.innowise.userservice.exception;

/** Thrown when a user that was looked up by id or by email does not exist. */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(Long id) {
    super(String.format("User with id %d not found", id));
  }

  private UserNotFoundException(String message) {
    super(message);
  }

  /**
   * Creates the exception for a lookup by email. The message does not name the email: it reaches
   * the response and the log, and an email is personal data.
   *
   * @return the exception for an email that belongs to nobody
   */
  public static UserNotFoundException forEmail() {
    return new UserNotFoundException("User with the given email not found");
  }

  /**
   * Creates the exception for a lookup by registration id. The message does not repeat the id:
   * the caller knows it, and it is not needed in a response.
   *
   * @return the exception for a registration id that no profile was created with
   */
  public static UserNotFoundException forRegistration() {
    return new UserNotFoundException("User with the given registration id not found");
  }
}
