package com.grechanikovars.userservice.controller;

import com.grechanikovars.userservice.dto.request.CardRequest;
import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.dto.response.PageResponse;
import com.grechanikovars.userservice.service.PaymentCardService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
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
 * REST API for payment cards.
 *
 * <p>Creating a card and listing the cards of one user live under the owner:
 * {@code /api/users/{userId}/cards}. Everything that works with a single card, and the filtered
 * list of all cards, lives under {@code /api/cards}. Card numbers are masked in every response.
 */
@RestController
@RequestMapping("/api")
public class PaymentCardController {

  /**
   * Properties the card list may be sorted by; {@code id} is always allowed. The card number is
   * deliberately absent: the order of full numbers would reveal information about numbers that
   * are masked in every response.
   */
  private static final SortWhitelist CARD_SORT = SortWhitelist.of(
    "holder", "expirationDate", "active", "createdAt");

  private final PaymentCardService paymentCardService;

  public PaymentCardController(PaymentCardService paymentCardService) {
    this.paymentCardService = paymentCardService;
  }

  /**
   * Creates a card for the given user. Returns 201 with a {@code Location} header pointing to
   * the new card under {@code /api/cards}.
   */
  @PostMapping("/users/{userId}/cards")
  public ResponseEntity<CardResponse> create(
    @PathVariable Long userId, @Valid @RequestBody CardRequest request) {
    CardResponse created = paymentCardService.create(userId, request);
    URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
      .path("/api/cards/{id}")
      .buildAndExpand(created.id())
      .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /** Returns all cards of a user. A user has at most five, so the list is not paged. */
  @GetMapping("/users/{userId}/cards")
  public ResponseEntity<List<CardResponse>> getAllByUserId(@PathVariable Long userId) {
    return ResponseEntity.ok(paymentCardService.getAllByUserId(userId));
  }

  /** Returns one card. */
  @GetMapping("/cards/{id}")
  public ResponseEntity<CardResponse> getById(@PathVariable Long id) {
    return ResponseEntity.ok(paymentCardService.getById(id));
  }

  /**
   * Returns a page of cards, optionally filtered by a part of the owner's name and surname.
   * Sorting is limited to {@link #CARD_SORT}.
   */
  @GetMapping("/cards")
  public ResponseEntity<PageResponse<CardResponse>> getAll(
    @RequestParam(required = false) String name,
    @RequestParam(required = false) String surname,
    @PageableDefault(size = 20, sort = "id") Pageable pageable) {
    Page<CardResponse> page =
      paymentCardService.getAll(name, surname, CARD_SORT.apply(pageable));
    return ResponseEntity.ok(PageResponse.from(page));
  }

  /** Replaces the number, holder and expiration date of a card; the owner never changes. */
  @PutMapping("/cards/{id}")
  public ResponseEntity<CardResponse> update(
    @PathVariable Long id, @Valid @RequestBody CardRequest request) {
    return ResponseEntity.ok(paymentCardService.update(id, request));
  }

  /** Activates a card. Repeating the call has no further effect. */
  @PatchMapping("/cards/{id}/activate")
  public ResponseEntity<CardResponse> activate(@PathVariable Long id) {
    return ResponseEntity.ok(paymentCardService.setActive(id, true));
  }

  /** Deactivates a card. Repeating the call has no further effect. */
  @PatchMapping("/cards/{id}/deactivate")
  public ResponseEntity<CardResponse> deactivate(@PathVariable Long id) {
    return ResponseEntity.ok(paymentCardService.setActive(id, false));
  }

  /** Deletes a card. */
  @DeleteMapping("/cards/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    paymentCardService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
