package com.innowise.userservice.exception;

/**
 * Thrown (and sent as a 409) when a profile with the same registration id already exists. The
 * Authentication Service chooses a new id for every attempt, so this means a caller bug or a
 * repeated request; it is recognized by the UNIQUE constraint, because the check and the insert
 * of two parallel requests cannot be made atomic in the service.
 */
public class RegistrationIdAlreadyUsedException extends RuntimeException {

  public RegistrationIdAlreadyUsedException() {
    super("A profile was already created for this registration");
  }
}
