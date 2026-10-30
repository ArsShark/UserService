package com.innowise.userservice.exception;

/** Thrown when a payment card with the requested id does not exist. */
public class PaymentCardNotFoundException extends RuntimeException {

  public PaymentCardNotFoundException(Long id) {
    super(String.format("Payment card with id %d not found", id));
  }
}
