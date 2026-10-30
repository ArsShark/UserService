package com.innowise.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Payment card that belongs to exactly one {@link User}. */

@Entity
@Table(name = "payment_cards")
@Getter
@Setter
@NoArgsConstructor
public class PaymentCard extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Setter(AccessLevel.NONE)
  private Long id;

  /**
   * Owner of the card. This is the owning side of the association. {@code ManyToOne} is EAGER
   * by default, so LAZY is set explicitly. The setter is package-private: only
   * {@link User#addCard} and {@link User#removeCard} keep both sides in sync.
   */

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @Setter(AccessLevel.PACKAGE)
  private User user;

  @Column(name = "number", nullable = false, length = 19, unique = true)
  private String number;

  @Column(name = "holder", nullable = false, length = 100)
  private String holder;

  @Column(name = "expiration_date", nullable = false)
  private LocalDate expirationDate;

  @Column(name = "active", nullable = false)
  private boolean active = true;
}
