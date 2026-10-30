package com.innowise.userservice.security;

import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.exception.PaymentCardNotFoundException;
import com.innowise.userservice.repository.PaymentCardRepository;
import com.innowise.userservice.repository.UserRepository;
import com.innowise.userservice.service.EmailNormalizer;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Decides whether an ordinary user may touch a resource: is it his own.
 *
 * <p>The methods are called from the expressions of {@link AccessRules} as {@code @access}, and
 * only for a caller who is not an admin. The profile id of the caller is the claim
 * {@value #CLAIM_USER_ID} of his access token. A caller without that claim, or one who is not
 * authenticated with a token, owns nothing.
 *
 * <p>Ownership of a user is a comparison of ids and needs no database. Ownership of a card needs
 * one small query for the id of its owner; the owner of a card never changes, so there is no race
 * between the check and the operation.
 */
@Component("access")
public class AccessPolicy {

  /** The claim that holds the id of the profile of the caller in this service. */
  public static final String CLAIM_USER_ID = "userId";

  private final UserRepository userRepository;
  private final PaymentCardRepository paymentCardRepository;

  /**
   * Creates the policy.
   *
   * @param userRepository reads the stored email of a user
   * @param paymentCardRepository reads the owner of a card
   */
  public AccessPolicy(UserRepository userRepository,
      PaymentCardRepository paymentCardRepository) {
    this.userRepository = userRepository;
    this.paymentCardRepository = paymentCardRepository;
  }

  /**
   * Tells whether the caller is the user with the given id.
   *
   * @param authentication the caller
   * @param userId the id of the user in the request
   * @return {@code true} if the profile id of the caller is {@code userId}; no database access
   */
  public boolean ownsUser(Authentication authentication, Long userId) {
    return profileIdOf(authentication).filter(id -> id.equals(userId)).isPresent();
  }

  /**
   * Tells whether the caller owns the card.
   *
   * <p>A card of somebody else gets the very same {@link PaymentCardNotFoundException} as a card
   * that does not exist, so a caller cannot find out which card ids are taken. A caller without a
   * profile gets {@code false} (a 403) without a lookup.
   *
   * @param authentication the caller
   * @param cardId the id of the card in the request
   * @return {@code true} if the caller owns the card
   * @throws PaymentCardNotFoundException if the card does not exist or belongs to somebody else
   */
  public boolean ownsCard(Authentication authentication, Long cardId) {
    Optional<Long> caller = profileIdOf(authentication);
    if (caller.isEmpty()) {
      return false;
    }
    Optional<Long> owner = paymentCardRepository.findUserIdById(cardId);
    if (owner.isEmpty() || !owner.get().equals(caller.get())) {
      throw new PaymentCardNotFoundException(cardId);
    }
    return true;
  }

  /**
   * Tells whether a request that replaces a user keeps his email. The emails are compared in the
   * stored form, so spaces and letter case do not count as a change.
   *
   * @param userId the id of the user in the request
   * @param request the new data of the user, already validated
   * @return {@code true} if the email is the same as the stored one, or if the user does not
   *     exist (the service then answers 404 and nothing is changed)
   */
  public boolean keepsEmail(Long userId, UserRequest request) {
    String requested = EmailNormalizer.normalize(request.email());
    return userRepository.findEmailById(userId)
        .map(requested::equals)
        .orElse(true);
  }

  private static Optional<Long> profileIdOf(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof Jwt jwt
        && jwt.getClaims().get(CLAIM_USER_ID) instanceof Number id) {
      return Optional.of(id.longValue());
    }
    return Optional.empty();
  }
}
