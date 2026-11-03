package com.innowise.userservice.exception;

/**
 * Thrown when a card number is already registered. The message deliberately does not contain
 * the number, to keep card data out of logs.
 */
public class CardNumberAlreadyExistsException extends RuntimeException {

  public CardNumberAlreadyExistsException() {
    super("A payment card with this number already exists");
  }
}
