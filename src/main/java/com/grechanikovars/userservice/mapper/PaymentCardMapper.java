package com.grechanikovars.userservice.mapper;

import com.grechanikovars.userservice.dto.request.CardRequest;
import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.entity.PaymentCard;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/** Converts between {@link PaymentCard} and card DTOs. */
@Mapper(
  componentModel = "spring",
  unmappedTargetPolicy = ReportingPolicy.ERROR,
  uses = CardNumberMasker.class)
public interface PaymentCardMapper {

  /** Maps a card to its response; the number is masked and the owner is reduced to its id. */
  @Mapping(target = "userId", source = "user.id")
  @Mapping(target = "maskedNumber", source = "number", qualifiedByName = "maskCardNumber")
  CardResponse toResponse(PaymentCard card);

  /**
   * Creates a new card from a request. The owner is not set here: the service attaches the card
   * with {@code User.addCard}. A new card is always active.
   */
  @Mapping(target = "active", ignore = true)
  PaymentCard toEntity(CardRequest request);

  /** Replaces the editable fields of an existing card; id, owner and active flag are kept. */
  @Mapping(target = "active", ignore = true)
  void updateEntity(CardRequest request, @MappingTarget PaymentCard card);
}
