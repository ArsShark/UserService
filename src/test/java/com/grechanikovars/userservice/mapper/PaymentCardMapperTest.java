package com.grechanikovars.userservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.grechanikovars.userservice.dto.request.CardRequest;
import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.entity.PaymentCard;
import com.grechanikovars.userservice.entity.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Tests of the generated {@link PaymentCardMapper}; no database is involved. */
@SpringJUnitConfig(PaymentCardMapperImpl.class)
class PaymentCardMapperTest {

  @Autowired
  private PaymentCardMapper mapper;

  @Test
  void toResponseMapsAllFieldsAndMasksTheNumber() {
    User owner = MapperTestEntities.user(7L);
    PaymentCard card = MapperTestEntities.card(11L, owner, "4000000000000001");

    CardResponse response = mapper.toResponse(card);

    assertAll(
      () -> assertEquals(11L, response.id()),
      () -> assertEquals(7L, response.userId()),
      () -> assertEquals("************0001", response.maskedNumber()),
      () -> assertEquals("JOHN SMITH", response.holder()),
      () -> assertEquals(LocalDate.of(2032, 12, 31), response.expirationDate()),
      () -> assertTrue(response.active()),
      () -> assertEquals(MapperTestEntities.CREATED_AT, response.createdAt()),
      () -> assertEquals(MapperTestEntities.UPDATED_AT, response.updatedAt()));
  }

  @Test
  void toEntityCopiesRequestFieldsAndKeepsDefaults() {
    CardRequest request =
      new CardRequest("4000000000000001", "JOHN SMITH", LocalDate.of(2032, 12, 31));

    PaymentCard card = mapper.toEntity(request);

    assertAll(
      () -> assertNull(card.getId()),
      () -> assertNull(card.getUser()),
      () -> assertEquals("4000000000000001", card.getNumber()),
      () -> assertEquals("JOHN SMITH", card.getHolder()),
      () -> assertEquals(LocalDate.of(2032, 12, 31), card.getExpirationDate()),
      () -> assertTrue(card.isActive()));
  }

  @Test
  void updateEntityReplacesRequestFieldsAndKeepsIdOwnerAndActiveFlag() {
    User owner = MapperTestEntities.user(7L);
    PaymentCard card = MapperTestEntities.card(11L, owner, "4000000000000001");
    card.setActive(false);
    CardRequest request =
      new CardRequest("5000000000000002", "JANE SMITH", LocalDate.of(2034, 1, 31));

    mapper.updateEntity(request, card);

    assertAll(
      () -> assertEquals(11L, card.getId()),
      () -> assertSame(owner, card.getUser()),
      () -> assertEquals("5000000000000002", card.getNumber()),
      () -> assertEquals("JANE SMITH", card.getHolder()),
      () -> assertEquals(LocalDate.of(2034, 1, 31), card.getExpirationDate()),
      () -> assertFalse(card.isActive()));
  }
}
