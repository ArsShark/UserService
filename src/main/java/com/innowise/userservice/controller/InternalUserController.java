package com.innowise.userservice.controller;

import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.dto.response.UserResponse;
import com.innowise.userservice.service.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the other services, mounted at {@code /internal/users}.
 *
 * <p>The Authentication Service calls the first two while a user registers, before the user has
 * a token: it creates the profile first and deletes it again if it cannot save the account. The
 * Order Service calls the lookup by email with the email of its caller. The security chain lets
 * only a caller with the right {@code X-Internal-Key} in. The prefix is not under
 * {@code /api/**} on purpose: the Gateway routes only {@code /api/**} to the outside, so these
 * endpoints can never be reached through it.
 *
 * <p>The endpoints use the same service methods as the endpoints of {@link UserController}, so
 * the validation, the rules and the error format are the same.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

  /** The header with the registration id of the attempt that creates the profile. */
  public static final String REGISTRATION_ID_HEADER = "X-Registration-Id";

  private final UserService userService;

  public InternalUserController(UserService userService) {
    this.userService = userService;
  }

  /**
   * Creates a user profile. Returns 201 with the profile; the caller reads the id from the body,
   * so there is no {@code Location} header.
   *
   * <p>The optional header {@code X-Registration-Id} is a UUID that the caller chose for this
   * attempt to register. It is stored with the profile and never returned. A value that is not a
   * UUID is a 400; the same id sent twice is a 409.
   */
  @PostMapping
  public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request,
      @RequestHeader(name = REGISTRATION_ID_HEADER, required = false) UUID registrationId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userService.create(request, registrationId));
  }

  /**
   * Deletes a user profile together with its cards: the rollback of a registration. A missing
   * profile is a 404, and the caller must treat that as "already rolled back".
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Finds a profile by email and answers with the same {@link UserResponse} as the public
   * endpoints, without cards.
   *
   * <p>The email is a query parameter and not a path segment, because a path would have to
   * carry the dots and the {@code @} of an address. The controller passes it on as it came; the
   * service normalizes it. A missing parameter is a 400. An unknown email is a 404, and so is a
   * blank one, because no profile has a blank email.
   */
  @GetMapping("/by-email")
  public ResponseEntity<UserResponse> getByEmail(@RequestParam String email) {
    return ResponseEntity.ok(userService.getByEmail(email));
  }
}
