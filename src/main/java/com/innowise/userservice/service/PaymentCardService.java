package com.innowise.userservice.service;

import com.innowise.userservice.dto.request.CardRequest;
import com.innowise.userservice.dto.response.CardResponse;
import com.innowise.userservice.entity.PaymentCard;
import com.innowise.userservice.entity.User;
import com.innowise.userservice.exception.CardLimitExceededException;
import com.innowise.userservice.exception.CardNumberAlreadyExistsException;
import com.innowise.userservice.exception.PaymentCardNotFoundException;
import com.innowise.userservice.exception.UserNotFoundException;
import com.innowise.userservice.mapper.PaymentCardMapper;
import com.innowise.userservice.repository.PaymentCardRepository;
import com.innowise.userservice.repository.UserRepository;
import com.innowise.userservice.repository.specification.PaymentCardSpecifications;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for payment cards: the card limit, uniqueness of numbers, transactions and
 * conversion to DTOs.
 *
 * <p>The class is read-only by default; every method that writes opts in with
 * {@link Transactional}. Entities are converted to DTOs inside the transaction, because
 * open-in-view is disabled and lazy associations cannot be read after it ends.
 * <p>Every change of a card evicts its owner from the user cache, because the cached user
 * contains its cards. The eviction runs inside the transaction of the method, just before the
 * commit; a read that slips in between can cache stale data for at most the TTL of the cache.
 */
@Service
@Transactional(readOnly = true)
public class PaymentCardService {

  /** The maximum number of cards one user may own. */
  private static final int MAX_CARDS_PER_USER = 5;

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentCardService.class);

  private final PaymentCardRepository paymentCardRepository;
  private final UserRepository userRepository;
  private final PaymentCardMapper paymentCardMapper;
  private final Clock clock;
  private final UserCacheEvictor userCacheEvictor;

  public PaymentCardService(
    PaymentCardRepository paymentCardRepository,
    UserRepository userRepository,
    PaymentCardMapper paymentCardMapper,
    Clock clock,
    UserCacheEvictor userCacheEvictor) {
    this.paymentCardRepository = paymentCardRepository;
    this.userRepository = userRepository;
    this.paymentCardMapper = paymentCardMapper;
    this.clock = clock;
    this.userCacheEvictor = userCacheEvictor;
  }

  /**
   * Creates an active card for a user.
   *
   * <p>The owner row is locked first, so two concurrent requests for the same user cannot both
   * pass the limit check. The lock is released when the transaction ends.
   * <p>Evicts the owner of the card from the user cache.
   *
   * @param userId owner id
   * @param request card data
   * @return the created card
   * @throws UserNotFoundException if there is no such user
   * @throws CardLimitExceededException if the user already has the maximum number of cards
   * @throws CardNumberAlreadyExistsException if the number is already registered
   */
  @Transactional
  public CardResponse create(Long userId, CardRequest request) {
    User owner = userRepository.findByIdForUpdate(userId)
      .orElseThrow(() -> new UserNotFoundException(userId));
    if (paymentCardRepository.countByUserId(userId) >= MAX_CARDS_PER_USER) {
      throw new CardLimitExceededException(userId, MAX_CARDS_PER_USER);
    }
    if (paymentCardRepository.existsByNumber(request.number())) {
      throw new CardNumberAlreadyExistsException();
    }
    PaymentCard card = paymentCardMapper.toEntity(request);
    owner.addCard(card);
    // Explicit save: the id is needed for the response and for the log message.
    PaymentCard saved = paymentCardRepository.save(card);
    LOGGER.info("Created payment card with id {} for user with id {}", saved.getId(), userId);
    userCacheEvictor.evict(userId);
    return paymentCardMapper.toResponse(saved);
  }

  /**
   * Returns a card by id.
   *
   * @param id card id
   * @return the card with a masked number
   * @throws PaymentCardNotFoundException if there is no such card
   */
  public CardResponse getById(Long id) {
    return paymentCardMapper.toResponse(getCardOrThrow(id));
  }

  /**
   * Returns a page of cards, optionally filtered by the name and surname of the owner.
   *
   * @param name text the owner's name must contain (ignoring case), {@code null} or blank for any
   * @param surname text the owner's surname must contain (ignoring case), {@code null} or blank
   *                for any
   * @param pageable paging and sorting
   * @return the requested page
   */
  public Page<CardResponse> getAll(String name, String surname, Pageable pageable) {
    return paymentCardRepository
      .findAll(PaymentCardSpecifications.ownerMatches(name, surname), pageable)
      .map(paymentCardMapper::toResponse);
  }

  /**
   * Returns all cards of a user.
   *
   * @param userId owner id
   * @return the cards of the user, empty if the user has none
   * @throws UserNotFoundException if there is no such user; this keeps "no cards" apart from
   *     "no user"
   */
  public List<CardResponse> getAllByUserId(Long userId) {
    if (!userRepository.existsById(userId)) {
      throw new UserNotFoundException(userId);
    }
    return paymentCardRepository.findAllByUserId(userId).stream()
      .map(paymentCardMapper::toResponse)
      .toList();
  }

  /**
   * Replaces number, holder and expiration date of a card. The owner and the active flag are
   * not changed.
   * <p>Evicts the owner of the card from the user cache.
   *
   * @param id card id
   * @param request new card data
   * @return the updated card
   * @throws PaymentCardNotFoundException if there is no such card
   * @throws CardNumberAlreadyExistsException if the new number belongs to another card
   */
  @Transactional
  public CardResponse update(Long id, CardRequest request) {
    PaymentCard card = getCardOrThrow(id);
    if (!request.number().equals(card.getNumber())
      && paymentCardRepository.existsByNumber(request.number())) {
      throw new CardNumberAlreadyExistsException();
    }
    paymentCardMapper.updateEntity(request, card);
    // Flush now: updatedAt is filled by auditing on flush, and the response must contain it.
    PaymentCard saved = paymentCardRepository.saveAndFlush(card);
    CardResponse response = paymentCardMapper.toResponse(saved);
    userCacheEvictor.evict(response.userId());
    LOGGER.info("Updated payment card with id {}", id);
    return response;
  }

  /**
   * Activates or deactivates a card.
   * <p>Evicts the owner of the card from the user cache.
   *
   * @param id card id
   * @param active the new value of the flag
   * @return the card after the change
   * @throws PaymentCardNotFoundException if there is no such card
   */
  @Transactional
  public CardResponse setActive(Long id, boolean active) {
    int updatedRows = paymentCardRepository.updateActiveById(id, active, Instant.now(clock));
    if (updatedRows == 0) {
      throw new PaymentCardNotFoundException(id);
    }
    LOGGER.info("Set active={} for payment card with id {}", active, id);
    // The bulk update cleared the persistence context, so this read returns the new state.
    CardResponse response = paymentCardMapper.toResponse(getCardOrThrow(id));
    userCacheEvictor.evict(response.userId());
    return response;
  }

  /**
   * Deletes a card.
   * <p>Evicts the owner of the card from the user cache.
   *
   * @param id card id
   * @throws PaymentCardNotFoundException if there is no such card
   */
  @Transactional
  public void delete(Long id) {
    PaymentCard card = getCardOrThrow(id);
    // Read the owner id first; the owner is a lazy proxy and its id is available without loading it.
    Long ownerId = card.getUser().getId();
    paymentCardRepository.delete(card);
    userCacheEvictor.evict(ownerId);
    LOGGER.info("Deleted payment card with id {}", id);
  }

  private PaymentCard getCardOrThrow(Long id) {
    return paymentCardRepository.findById(id)
      .orElseThrow(() -> new PaymentCardNotFoundException(id));
  }
}
