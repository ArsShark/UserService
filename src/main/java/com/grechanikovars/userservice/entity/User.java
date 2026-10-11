package com.grechanikovars.userservice.entity;

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

  // Inverse side of the association: the foreign key lives in payment_cards.
  // No setter: replacing the collection instance breaks orphanRemoval.
  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
  @Setter(AccessLevel.NONE)
  private List<PaymentCard> cards = new ArrayList<>();

  public void addCard(PaymentCard card) {
    cards.add(card);
    card.setUser(this);
  }

  public void removeCard(PaymentCard card) {
    cards.remove(card);
    card.setUser(null);
  }
}
