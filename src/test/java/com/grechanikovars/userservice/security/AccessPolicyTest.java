package com.grechanikovars.userservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.exception.PaymentCardNotFoundException;
import com.grechanikovars.userservice.repository.PaymentCardRepository;
import com.grechanikovars.userservice.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests of {@link AccessPolicy}: plain Mockito, no Spring. The caller is a real
 * {@link JwtAuthenticationToken}, because the policy reads the claim of its token. The ids of
 * the caller (7), of the other user (8) and of the card (42) differ on purpose, so mixing them up
 * fails a test.
 */
@ExtendWith(MockitoExtension.class)
class AccessPolicyTest {

  private static final long CALLER = 7L;
  private static final long OTHER = 8L;
  private static final long CARD = 42L;

  @Mock
  private UserRepository userRepository;

  @Mock
  private PaymentCardRepository paymentCardRepository;

  private AccessPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new AccessPolicy(userRepository, paymentCardRepository);
  }

  private static Authentication caller(Object profileClaim) {
    Jwt.Builder jwt = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("role", "USER");
    if (profileClaim != null) {
      jwt.claim("userId", profileClaim);
    }
    return new JwtAuthenticationToken(jwt.build(), List.of());
  }

  private static UserRequest request(String email) {
    return new UserRequest("Anna", "Petrova", LocalDate.of(1990, 5, 17), email);
  }

  @Test
  void aUserOwnsHisOwnProfileOnly() {
    Authentication authentication = caller(CALLER);

    assertAll(
        () -> assertTrue(policy.ownsUser(authentication, CALLER)),
        () -> assertFalse(policy.ownsUser(authentication, OTHER)),
        () -> assertFalse(policy.ownsUser(authentication, null)));
    verifyNoInteractions(userRepository, paymentCardRepository);
  }

  /** A number in a token may be read as an Integer or a Long, depending on the JSON library. */
  @Test
  void theProfileIdMayBeAnyKindOfNumber() {
    assertAll(
        () -> assertTrue(policy.ownsUser(caller(7), CALLER)),
        () -> assertTrue(policy.ownsUser(caller(7L), CALLER)));
  }

  @Test
  void aCallerWithoutAUsableProfileClaimOwnsNothing() {
    Authentication notANumber = caller("7");
    Authentication noClaim = caller(null);
    Authentication notAToken = UsernamePasswordAuthenticationToken.authenticated(
        "alice", null, AuthorityUtils.createAuthorityList("ROLE_USER"));

    assertAll(
        () -> assertFalse(policy.ownsUser(notANumber, CALLER)),
        () -> assertFalse(policy.ownsUser(noClaim, CALLER)),
        () -> assertFalse(policy.ownsUser(notAToken, CALLER)),
        () -> assertFalse(policy.ownsUser(null, CALLER)),
        () -> assertFalse(policy.ownsCard(noClaim, CARD)),
        () -> assertFalse(policy.ownsCard(notAToken, CARD)));
    verifyNoInteractions(userRepository, paymentCardRepository);
  }

  @Test
  void theOwnerOfACardOwnsIt() {
    when(paymentCardRepository.findUserIdById(CARD)).thenReturn(Optional.of(CALLER));

    assertTrue(policy.ownsCard(caller(CALLER), CARD));
  }

  /**
   * A card of somebody else and a card that does not exist give the same exception, with a
   * message that differs only by the id that the caller sent himself.
   */
  @Test
  void aForeignCardLooksLikeAMissingCard() {
    when(paymentCardRepository.findUserIdById(CARD)).thenReturn(Optional.of(OTHER));
    when(paymentCardRepository.findUserIdById(CARD + 1)).thenReturn(Optional.empty());
    Authentication authentication = caller(CALLER);

    PaymentCardNotFoundException foreign = assertThrows(PaymentCardNotFoundException.class,
        () -> policy.ownsCard(authentication, CARD));
    PaymentCardNotFoundException missing = assertThrows(PaymentCardNotFoundException.class,
        () -> policy.ownsCard(authentication, CARD + 1));

    assertAll(
        () -> assertEquals(new PaymentCardNotFoundException(CARD).getMessage(),
            foreign.getMessage()),
        () -> assertEquals(new PaymentCardNotFoundException(CARD + 1).getMessage(),
            missing.getMessage()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"anna@example.com", "ANNA@Example.COM", "  anna@example.com "})
  void theSameEmailInAnyFormIsNotAChange(String requested) {
    when(userRepository.findEmailById(CALLER)).thenReturn(Optional.of("anna@example.com"));

    assertTrue(policy.keepsEmail(CALLER, request(requested)));
  }

  @Test
  void anotherEmailIsAChange() {
    when(userRepository.findEmailById(CALLER)).thenReturn(Optional.of("anna@example.com"));

    assertFalse(policy.keepsEmail(CALLER, request("new@example.com")));
  }

  /** The service answers 404 for a user that does not exist; nothing is changed by the check. */
  @Test
  void aMissingUserIsLeftToTheService() {
    when(userRepository.findEmailById(CALLER)).thenReturn(Optional.empty());

    assertTrue(policy.keepsEmail(CALLER, request("new@example.com")));
  }
}
