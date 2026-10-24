package com.grechanikovars.userservice.service;

import java.util.Locale;

/**
 * The one rule for the form in which an email is compared and stored: without surrounding
 * spaces and in lower case.
 *
 * <p>The users service stores emails like this, and the access checks compare the email of a
 * request with the stored one in the same form, so the two can never disagree. The case rules
 * of the root locale are used on purpose: the rules of the default locale of the server would
 * turn an {@code I} into a dotless letter in Turkish.
 */
public final class EmailNormalizer {

  private EmailNormalizer() {
  }

  /**
   * Brings an email to the stored form.
   *
   * @param email an email as sent by a client
   * @return the email without surrounding spaces, in lower case
   */
  public static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
