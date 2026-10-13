package com.grechanikovars.userservice.exception;

/**
 * Thrown when an email is already used by another user. The message deliberately does not
 * contain the email, to keep personal data out of logs.
 */
public class EmailAlreadyExistsException extends RuntimeException {

  public EmailAlreadyExistsException() {
    super("A user with this email already exists");
  }
}
