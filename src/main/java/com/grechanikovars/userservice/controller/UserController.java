package com.grechanikovars.userservice.controller;

import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.PageResponse;
import com.grechanikovars.userservice.dto.response.UserResponse;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import com.grechanikovars.userservice.security.AccessRules;
import com.grechanikovars.userservice.service.UserService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST API for users, mounted at {@code /api/users}.
 *
 * <p>The controller only translates HTTP into service calls and back: request bodies are
 * validated here, business rules live in {@link UserService}, and failures are turned into
 * responses by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

  /** Properties the user list may be sorted by; {@code id} is always allowed. */
  private static final SortWhitelist USER_SORT = SortWhitelist.of(
    "name", "surname", "birthDate", "email", "active", "createdAt");

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  /** Creates a user. Returns 201 with a {@code Location} header pointing to the new user. */
  @PostMapping
  public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
    UserResponse created = userService.create(request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
      .path("/{id}")
      .buildAndExpand(created.id())
      .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /** Returns a user together with the user's cards: an admin, or the user himself. */
  @PreAuthorize(AccessRules.OWN_USER)
  @GetMapping("/{id}")
  public ResponseEntity<UserWithCardsResponse> getById(@PathVariable Long id) {
    return ResponseEntity.ok(userService.getById(id));
  }

  /**
   * Returns a page of users, optionally filtered by a part of the name and of the surname.
   * Sorting is limited to {@link #USER_SORT}; the page size is capped by
   * {@code spring.data.web.pageable.max-page-size}.
   */
  @GetMapping
  public ResponseEntity<PageResponse<UserResponse>> getAll(
    @RequestParam(required = false) String name,
    @RequestParam(required = false) String surname,
    @PageableDefault(size = 20, sort = "id") Pageable pageable) {
    Page<UserResponse> page = userService.getAll(name, surname, USER_SORT.apply(pageable));
    return ResponseEntity.ok(PageResponse.from(page));
  }

  /**
   * Replaces all user data except the active flag. A user may replace his own data but not his
   * email; see {@link AccessRules#OWN_PROFILE_UPDATE}.
   */
  @PreAuthorize(AccessRules.OWN_PROFILE_UPDATE)
  @PutMapping("/{id}")
  public ResponseEntity<UserResponse> update(
    @PathVariable Long id, @Valid @RequestBody UserRequest request) {
    return ResponseEntity.ok(userService.update(id, request));
  }

  /** Activates a user. Repeating the call has no further effect. */
  @PatchMapping("/{id}/activate")
  public ResponseEntity<UserResponse> activate(@PathVariable Long id) {
    return ResponseEntity.ok(userService.setActive(id, true));
  }

  /** Deactivates a user. Repeating the call has no further effect. */
  @PatchMapping("/{id}/deactivate")
  public ResponseEntity<UserResponse> deactivate(@PathVariable Long id) {
    return ResponseEntity.ok(userService.setActive(id, false));
  }

  /** Deletes a user together with the user's cards. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
