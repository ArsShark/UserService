package com.grechanikovars.userservice.controller;

import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.UserResponse;
import com.grechanikovars.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the other services, mounted at {@code /internal/users}.
 *
 * <p>The Gateway calls them while a user registers, before the user has a token: it creates the
 * profile first and deletes it again if the Authentication Service then refuses the account.
 * The security chain lets only a caller with the right {@code X-Internal-Key} in. The prefix is
 * not under {@code /api/**} on purpose: the Gateway routes only {@code /api/**} to the outside,
 * so these endpoints can never be reached through it.
 *
 * <p>Both endpoints use the same service methods as the endpoints of {@link UserController}, so
 * the validation, the rules and the error format are the same.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

  private final UserService userService;

  public InternalUserController(UserService userService) {
    this.userService = userService;
  }

  /**
   * Creates a user profile. Returns 201 with the profile; the caller reads the id from the body,
   * so there is no {@code Location} header.
   */
  @PostMapping
  public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
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
}
