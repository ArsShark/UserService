package com.innowise.userservice.security;

/**
 * The method security expressions of the controllers, in one place.
 *
 * <p>The security chain decides by role and HTTP method whether a kind of caller may call an
 * endpoint at all. These rules decide the second question, for the endpoints that an ordinary
 * user may call: does the resource belong to him. An admin passes at once, without a lookup.
 * The expressions run before the controller method, so before the service and its cache: a
 * refused request never touches the data.
 *
 * <p>The names in {@code #id}, {@code #userId} and {@code #request} are the names of the
 * controller parameters (the compiler keeps them: Spring Boot compiles with {@code -parameters}).
 */
public final class AccessRules {

  /** The path variable {@code id} is the id of a user: an admin or the user himself. */
  public static final String OWN_USER =
      "hasRole('ADMIN') or @access.ownsUser(authentication, #id)";

  /** The path variable {@code userId} is the id of a user: an admin or the user himself. */
  public static final String OWN_USER_BY_USER_ID =
      "hasRole('ADMIN') or @access.ownsUser(authentication, #userId)";

  /** The path variable {@code id} is the id of a card: an admin or the owner of the card. */
  public static final String OWN_CARD =
      "hasRole('ADMIN') or @access.ownsCard(authentication, #id)";

  /**
   * A user is replaced: an admin may change everything; the user himself everything except the
   * email, which is also his login and the claim of his tokens.
   */
  public static final String OWN_PROFILE_UPDATE = """
      hasRole('ADMIN') or (@access.ownsUser(authentication, #id) \
      and @access.keepsEmail(#id, #request))""";

  /** Only an admin may call the endpoint. */
  public static final String ADMIN_ONLY = "hasRole('ADMIN')";

  private AccessRules() {
  }
}
