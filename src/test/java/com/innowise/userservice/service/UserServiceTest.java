package com.innowise.userservice.service;

import static com.innowise.userservice.service.ServiceTestEntities.user;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.userservice.dto.request.UserRequest;
import com.innowise.userservice.dto.response.UserResponse;
import com.innowise.userservice.dto.response.UserWithCardsResponse;
import com.innowise.userservice.entity.User;
import com.innowise.userservice.exception.EmailAlreadyExistsException;
import com.innowise.userservice.exception.UserNotFoundException;
import com.innowise.userservice.mapper.UserMapper;
import com.innowise.userservice.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests of {@link UserService} with plain Mockito: no Spring context, no database.
 *
 * <p>The repository and the mapper are mocks, so a failure points at the service logic itself;
 * the mapper has its own tests. The cache annotations and transactions need a Spring proxy and
 * are therefore not active here: they are covered by the caching tests and the integration tests.
 * Mockito runs with strict stubs, so a stubbing that the service never uses fails the test.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  private static final Long USER_ID = 7L;
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
  private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 5, 17);

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserMapper userMapper;

  private UserService userService;

  @BeforeEach
  void createService() {
    // Not @InjectMocks: the Clock is not a mock, and Mockito would pass null for it.
    userService = new UserService(userRepository, userMapper, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createChecksAndStoresNormalizedEmail() {
    UserRequest request = request("  Ivan.Petrov@Example.COM ");
    // The real mapper never copies the email, so the mapped entity has none.
    User mapped = user(null);
    mapped.setEmail(null);
    User saved = user(USER_ID);
    UserResponse expected = response(USER_ID, "ivan.petrov@example.com");
    when(userRepository.existsByEmail("ivan.petrov@example.com")).thenReturn(false);
    when(userMapper.toEntity(request)).thenReturn(mapped);
    when(userRepository.save(hasEmail("ivan.petrov@example.com"))).thenReturn(saved);
    when(userMapper.toResponse(saved)).thenReturn(expected);

    UserResponse actual = userService.create(request);

    assertAll(
      () -> assertSame(expected, actual),
      () -> assertEquals("ivan.petrov@example.com", mapped.getEmail()));
  }

  @Test
  void createRejectsEmailThatIsAlreadyUsed() {
    when(userRepository.existsByEmail("ivan@example.com")).thenReturn(true);

    assertThrows(EmailAlreadyExistsException.class,
      () -> userService.create(request(" IVAN@example.com ")));

    assertAll(
      () -> verify(userRepository, never()).save(any(User.class)),
      () -> verifyNoInteractions(userMapper));
  }

  @Test
  void createNormalizesEmailIndependentlyOfDefaultLocale() {
    Locale original = Locale.getDefault();
    // In the Turkish locale a plain toLowerCase() turns "I" into a dotless i.
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      when(userRepository.existsByEmail("info@example.com")).thenReturn(true);

      assertThrows(EmailAlreadyExistsException.class,
        () -> userService.create(request("INFO@Example.COM")));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void getByIdReturnsUserWithCards() {
    User found = user(USER_ID);
    UserWithCardsResponse expected = responseWithCards();
    when(userRepository.findByIdWithCards(USER_ID)).thenReturn(Optional.of(found));
    when(userMapper.toResponseWithCards(found)).thenReturn(expected);

    assertSame(expected, userService.getById(USER_ID));
  }

  @Test
  void getByIdRejectsUnknownUser() {
    when(userRepository.findByIdWithCards(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> userService.getById(USER_ID));

    verifyNoInteractions(userMapper);
  }

  @Test
  void getAllMapsEveryElementAndKeepsPageMetadata() {
    Pageable pageable = PageRequest.of(1, 2, Sort.by("surname"));
    User first = user(1L);
    User second = user(2L);
    UserResponse firstResponse = response(1L, "first@example.com");
    UserResponse secondResponse = response(2L, "second@example.com");
    // The content of the specification is covered by the repository tests, so any() is enough.
    when(userRepository.findAll(ArgumentMatchers.<Specification<User>>any(), eq(pageable)))
      .thenReturn(new PageImpl<>(List.of(first, second), pageable, 5));
    when(userMapper.toResponse(first)).thenReturn(firstResponse);
    when(userMapper.toResponse(second)).thenReturn(secondResponse);

    Page<UserResponse> actual = userService.getAll("iva", "pet", pageable);

    assertAll(
      () -> assertEquals(List.of(firstResponse, secondResponse), actual.getContent()),
      () -> assertEquals(5, actual.getTotalElements()),
      () -> assertEquals(1, actual.getNumber()),
      () -> assertEquals(2, actual.getSize()));
  }

  @Test
  void updateSkipsUniquenessCheckWhenNormalizedEmailIsUnchanged() {
    UserRequest request = request("  IVAN@example.com");
    User existing = user(USER_ID);
    UserResponse expected = response(USER_ID, "ivan@example.com");
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
    when(userRepository.saveAndFlush(existing)).thenReturn(existing);
    when(userMapper.toResponse(existing)).thenReturn(expected);

    UserResponse actual = userService.update(USER_ID, request);

    assertAll(
      () -> assertSame(expected, actual),
      () -> verify(userRepository, never()).existsByEmail(any()),
      () -> verify(userMapper).updateEntity(request, existing),
      () -> verify(userRepository).saveAndFlush(existing));
  }

  @Test
  void updateStoresNewEmailWhenItIsFree() {
    UserRequest request = request(" New@Example.com ");
    User existing = user(USER_ID);
    UserResponse expected = response(USER_ID, "new@example.com");
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
    when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
    when(userRepository.saveAndFlush(existing)).thenReturn(existing);
    when(userMapper.toResponse(existing)).thenReturn(expected);

    UserResponse actual = userService.update(USER_ID, request);

    assertAll(
      () -> assertSame(expected, actual),
      () -> assertEquals("new@example.com", existing.getEmail()),
      () -> verify(userMapper).updateEntity(request, existing));
  }

  @Test
  void updateRejectsEmailOfAnotherUser() {
    User existing = user(USER_ID);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
    when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

    assertThrows(EmailAlreadyExistsException.class,
      () -> userService.update(USER_ID, request("Taken@Example.com")));

    assertAll(
      () -> assertEquals("ivan@example.com", existing.getEmail()),
      () -> verify(userMapper, never()).updateEntity(any(UserRequest.class), any(User.class)),
      () -> verify(userRepository, never()).saveAndFlush(any(User.class)));
  }

  @Test
  void updateRejectsUnknownUser() {
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class,
      () -> userService.update(USER_ID, request("ivan@example.com")));

    assertAll(
      () -> verifyNoInteractions(userMapper),
      () -> verify(userRepository, never()).saveAndFlush(any(User.class)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void setActiveUpdatesTheFlagAndReadsTheUserAfterwards(boolean active) {
    User reloaded = user(USER_ID);
    UserResponse expected = response(USER_ID, "ivan@example.com");
    when(userRepository.updateActiveById(USER_ID, active, NOW)).thenReturn(1);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(reloaded));
    when(userMapper.toResponse(reloaded)).thenReturn(expected);

    UserResponse actual = userService.setActive(USER_ID, active);

    // The bulk update bypasses the persistence context, so the read has to come after it.
    InOrder order = inOrder(userRepository);
    assertAll(
      () -> assertSame(expected, actual),
      () -> order.verify(userRepository).updateActiveById(USER_ID, active, NOW),
      () -> order.verify(userRepository).findById(USER_ID));
  }

  @Test
  void setActiveRejectsUnknownUser() {
    when(userRepository.updateActiveById(USER_ID, true, NOW)).thenReturn(0);

    assertThrows(UserNotFoundException.class, () -> userService.setActive(USER_ID, true));

    assertAll(
      () -> verify(userRepository, never()).findById(any()),
      () -> verifyNoInteractions(userMapper));
  }

  @Test
  void setActiveRejectsUserDeletedAfterTheUpdate() {
    when(userRepository.updateActiveById(USER_ID, false, NOW)).thenReturn(1);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> userService.setActive(USER_ID, false));

    verifyNoInteractions(userMapper);
  }

  @Test
  void deleteRemovesTheLoadedEntity() {
    User existing = user(USER_ID);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userService.delete(USER_ID);

    assertAll(
      () -> verify(userRepository).delete(existing),
      () -> verify(userRepository, never()).deleteById(any()));
  }

  @Test
  void deleteRejectsUnknownUser() {
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> userService.delete(USER_ID));

    verify(userRepository, never()).delete(any(User.class));
  }

  /**
   * Matches the user whose email equals the given value at the moment of the call. It proves that
   * the service sets the normalized email before the entity reaches the repository.
   */
  private static User hasEmail(String email) {
    return argThat(candidate -> email.equals(candidate.getEmail()));
  }

  private static UserRequest request(String email) {
    return new UserRequest("Ivan", "Petrov", BIRTH_DATE, email);
  }

  private static UserResponse response(Long id, String email) {
    return new UserResponse(id, "Ivan", "Petrov", BIRTH_DATE, email, true, NOW, NOW);
  }

  private static UserWithCardsResponse responseWithCards() {
    return new UserWithCardsResponse(USER_ID, "Ivan", "Petrov", BIRTH_DATE, "ivan@example.com",
      true, NOW, NOW, List.of());
  }
}
