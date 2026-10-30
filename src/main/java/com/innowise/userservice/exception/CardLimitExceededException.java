package com.innowise.userservice.exception;

/** Thrown when a user already owns the maximum allowed number of active payment cards. */
public class CardLimitExceededException extends RuntimeException {

  public CardLimitExceededException(Long userId, int limit) {
    super(String.format("User with id %d already has the maximum of %d active payment cards",
      userId, limit));
  }
}
