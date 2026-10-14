package com.grechanikovars.userservice.mapper;

import org.mapstruct.Named;

/**
 * Masks payment card numbers before they leave the service.
 *
 * <p>The class is registered in {@link PaymentCardMapper} through {@code uses}. The method is
 * annotated with {@link Named}, so MapStruct applies it only where a mapping selects it by name.
 * Without the qualifier MapStruct would apply it to every {@code String} property.
 */
public final class CardNumberMasker {

  private static final int VISIBLE_CHARACTERS = 4;
  private static final String MASK = "*";

  private CardNumberMasker() {
  }

  /**
   * Replaces all but the last four characters with '*'.
   *
   * <p>A value of four characters or fewer is masked completely, so nothing is revealed about it.
   *
   * @param number the card number, may be {@code null}
   * @return the masked number, or {@code null} if the input is {@code null}
   */
  @Named("maskCardNumber")
  public static String mask(String number) {
    if (number == null) {
      return null;
    }
    int length = number.length();
    int maskedCount = length > VISIBLE_CHARACTERS ? length - VISIBLE_CHARACTERS : length;
    StringBuilder masked = new StringBuilder(length);
    masked.append(MASK.repeat(maskedCount));
    masked.append(number, maskedCount, length);
    return masked.toString();
  }
}
