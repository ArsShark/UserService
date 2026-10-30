package com.innowise.userservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Application user. A user owns payment cards; the limit of 5 active cards is enforced in the
 * service layer. Deactivated cards do not count towards the limit.
 */

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Setter(AccessLevel.NONE)
  private Long id;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "surname", nullable = false, length = 100)
  private String surname;

  @Column(name = "birth_date", nullable = false)
  private LocalDate birthDate;

  @Column(name = "email", nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  /**
   * Cards of the user. This is the inverse side of the association: the foreign key lives in
   * {@code payment_cards.user_id}. There is no setter, because replacing the collection
   * instance breaks {@code orphanRemoval}; use {@link #addCard} and {@link #removeCard}.
   */

  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
  @Setter(AccessLevel.NONE)
  private List<PaymentCard> cards = new ArrayList<>();

  /**
   * Adds the card to this user and sets the owning side of the association.
   *
   * @param card the card to add
   */

  public void addCard(PaymentCard card) {
    cards.add(card);
    card.setUser(this);
  }

  /**
   * Removes the card from this user; with {@code orphanRemoval} the card row is deleted.
   *
   * @param card the card to remove
   */

  public void removeCard(PaymentCard card) {
    cards.remove(card);
    card.setUser(null);
  }
}
