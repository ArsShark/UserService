package com.grechanikovars.userservice.service;

import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.UserResponse;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import com.grechanikovars.userservice.entity.User;
import com.grechanikovars.userservice.exception.EmailAlreadyExistsException;
import com.grechanikovars.userservice.exception.UserNotFoundException;
import com.grechanikovars.userservice.mapper.UserMapper;
import com.grechanikovars.userservice.repository.UserRepository;
import com.grechanikovars.userservice.repository.specification.UserSpecifications;
import com.grechanikovars.userservice.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for users: uniqueness rules, transactions and conversion to DTOs.
 *
 * <p>The class is read-only by default; every method that writes opts in with
 * {@link Transactional}. Entities are converted to DTOs inside the transaction, because
 * open-in-view is disabled and lazy associations cannot be read after it ends.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

  private static final Logger LOGGER = LogManager.getLogger(UserService.class);

  private final UserRepository userRepository;
  private final UserMapper userMapper;
  private final Clock clock;

  public UserService(UserRepository userRepository, UserMapper userMapper, Clock clock) {
    this.userRepository = userRepository;
    this.userMapper = userMapper;
    this.clock = clock;
  }

  /**
   * Creates an active user without cards.
   *
   * @param request user data; the email is normalized before it is checked and stored
   * @return the created user
   * @throws EmailAlreadyExistsException if the normalized email is already used
   */
  @Transactional
  public UserResponse create(UserRequest request) {
    String email = normalizeEmail(request.email());
    if (userRepository.existsByEmail(email)) {
      throw new EmailAlreadyExistsException();
    }
    User user = userMapper.toEntity(request);
    user.setEmail(email);
    User saved = userRepository.save(user);
    LOGGER.info("Created user with id {}", saved.getId());
    return userMapper.toResponse(saved);
  }
  /**
   * Returns a user together with all of its cards.
   *
   * <p>The result is cached by user id in the {@code users} cache. The entry is evicted when the
   * user is updated, activated, deactivated or deleted.
   * <p>The result is cached by user id in the {@code users} cache. The entry is evicted when the
   * user is updated, activated, deactivated or deleted, and when one of its cards changes.
   * @param id user id
   * @return the user with cards
   * @throws UserNotFoundException if there is no such user
   */
  @Cacheable(cacheNames = CacheConfig.USERS, key = "#id")
  public UserWithCardsResponse getById(Long id) {
    User user = userRepository.findByIdWithCards(id)
      .orElseThrow(() -> new UserNotFoundException(id));
    return userMapper.toResponseWithCards(user);
  }

  /**
   * Returns a page of users, optionally filtered by name and surname.
   *
   * @param name text the name must contain (ignoring case), {@code null} or blank for any
   * @param surname text the surname must contain (ignoring case), {@code null} or blank for any
   * @param pageable paging and sorting
   * @return the requested page, without cards
   */
  public Page<UserResponse> getAll(String name, String surname, Pageable pageable) {
    return userRepository.findAll(UserSpecifications.matches(name, surname), pageable)
      .map(userMapper::toResponse);
  }

  /**
   * Replaces name, surname, birth date and email of a user. The active flag and the cards are
   * not changed.
   * Evicts the user from the cache.
   *
   * @param id user id
   * @param request new user data
   * @return the updated user
   * @throws UserNotFoundException if there is no such user
   * @throws EmailAlreadyExistsException if the new email belongs to another user
   */
  @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
  @Transactional
  public UserResponse update(Long id, UserRequest request) {
    User user = getUserOrThrow(id);
    String email = normalizeEmail(request.email());
    if (!email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
      throw new EmailAlreadyExistsException();
    }
    userMapper.updateEntity(request, user);
    user.setEmail(email);
    // Flush now: updatedAt is filled by auditing on flush, and the response must contain it.
    User saved = userRepository.saveAndFlush(user);
    LOGGER.info("Updated user with id {}", id);
    return userMapper.toResponse(saved);
  }

  /**
   * Activates or deactivates a user. The cards keep their own active flags.
   * Evicts the user from the cache.
   *
   * @param id user id
   * @param active the new value of the flag
   * @return the user after the change
   * @throws UserNotFoundException if there is no such user
   */
  @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
  @Transactional
  public UserResponse setActive(Long id, boolean active) {
    int updatedRows = userRepository.updateActiveById(id, active, Instant.now(clock));
    if (updatedRows == 0) {
      throw new UserNotFoundException(id);
    }
    LOGGER.info("Set active={} for user with id {}", active, id);
    // The bulk update cleared the persistence context, so this read returns the new state.
    return userMapper.toResponse(getUserOrThrow(id));
  }

  /**
   * Deletes a user together with its cards.
   * Evicts the user from the cache.
   *
   * @param id user id
   * @throws UserNotFoundException if there is no such user
   */
  @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
  @Transactional
  public void delete(Long id) {
    User user = getUserOrThrow(id);
    userRepository.delete(user);
    LOGGER.info("Deleted user with id {}", id);
  }

  private User getUserOrThrow(Long id) {
    return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
  }

  private static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
