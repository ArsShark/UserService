package com.grechanikovars.userservice.exception;

/** Thrown when a user already owns the maximum allowed number of payment cards. */
public class CardLimitExceededException extends RuntimeException {

  public CardLimitExceededException(Long userId, int limit) {
    super(String.format("User with id %d already has the maximum of %d payment cards",
      userId, limit));
  }
}
