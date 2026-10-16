package com.grechanikovars.userservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grechanikovars.userservice.dto.request.UserRequest;
import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.dto.response.UserResponse;
import com.grechanikovars.userservice.dto.response.UserWithCardsResponse;
import com.grechanikovars.userservice.exception.EmailAlreadyExistsException;
import com.grechanikovars.userservice.exception.UserNotFoundException;
import com.grechanikovars.userservice.service.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests of {@link UserController}: paths, status codes, headers and JSON shape.
 * The service is a mock, so these tests do not touch a database; the full flow is covered by the
 * integration tests.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String VALID_USER_JSON = """
    {
      "name": "Anna",
      "surname": "Petrova",
      "birthDate": "1990-05-17",
      "email": "anna@example.com"
    }
    """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  private static UserResponse user(long id, boolean active) {
    return new UserResponse(id, "Anna", "Petrova", LocalDate.of(1990, 5, 17),
      "anna@example.com", active, NOW, NOW);
  }

  @Test
  void createReturns201WithLocationAndBody() throws Exception {
    when(userService.create(any(UserRequest.class))).thenReturn(user(1, true));

    mockMvc.perform(post("/api/users")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_USER_JSON)).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION, "http://localhost/api/users/1"),
      jsonPath("$.id").value(1),
      jsonPath("$.email").value("anna@example.com"),
      jsonPath("$.active").value(true));

    ArgumentCaptor<UserRequest> request = ArgumentCaptor.forClass(UserRequest.class);
    verify(userService).create(request.capture());
    assertEquals(
      new UserRequest("Anna", "Petrova", LocalDate.of(1990, 5, 17), "anna@example.com"),
      request.getValue());
  }

  @Test
  void createRejectsInvalidBodyWith400() throws Exception {
    String body = """
      {"name": "", "surname": "Petrova", "birthDate": "2999-01-01", "email": "not-an-email"}
      """;

    mockMvc.perform(post("/api/users")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors.length()").value(3),
      jsonPath("$.errors[0].field").value("birthDate"),
      jsonPath("$.errors[1].field").value("email"),
      jsonPath("$.errors[2].field").value("name"),
      content().string(not(containsString("not-an-email"))));

    verifyNoInteractions(userService);
  }

  @Test
  void createAnswers409ForDuplicateEmail() throws Exception {
    when(userService.create(any(UserRequest.class))).thenThrow(new EmailAlreadyExistsException());

    mockMvc.perform(post("/api/users")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_USER_JSON)).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(409));
  }

  @Test
  void getByIdReturnsUserWithMaskedCards() throws Exception {
    CardResponse card = new CardResponse(10L, 5L, "************0001", "ANNA PETROVA",
      LocalDate.of(2030, 12, 31), true, NOW, NOW);
    when(userService.getById(5L)).thenReturn(new UserWithCardsResponse(5L, "Anna", "Petrova",
      LocalDate.of(1990, 5, 17), "anna@example.com", true, NOW, NOW, List.of(card)));

    mockMvc.perform(get("/api/users/5")).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(5),
      jsonPath("$.cards.length()").value(1),
      jsonPath("$.cards[0].maskedNumber").value("************0001"),
      jsonPath("$.cards[0].number").doesNotExist());
  }

  @Test
  void getByIdAnswers404ForUnknownUser() throws Exception {
    when(userService.getById(99L)).thenThrow(new UserNotFoundException(99L));

    mockMvc.perform(get("/api/users/99")).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("User with id 99 not found"));
  }

  @Test
  void getByIdAnswers400ForNonNumericId() throws Exception {
    mockMvc.perform(get("/api/users/abc")).andExpect(status().isBadRequest());

    verifyNoInteractions(userService);
  }

  @Test
  void getAllUsesDefaultPagingAndWrapsThePage() throws Exception {
    when(userService.getAll(isNull(), isNull(), any(Pageable.class))).thenReturn(
      new PageImpl<>(List.of(user(1, true), user(2, true)), PageRequest.of(0, 20), 2));

    mockMvc.perform(get("/api/users")).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(2),
      jsonPath("$.content[0].id").value(1),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(2),
      jsonPath("$.totalPages").value(1));

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(userService).getAll(isNull(), isNull(), pageable.capture());
    assertAll(
      () -> assertEquals(0, pageable.getValue().getPageNumber()),
      () -> assertEquals(20, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by("id"), pageable.getValue().getSort()));
  }

  @Test
  void getAllPassesFiltersAndSortWithIdTiebreaker() throws Exception {
    when(userService.getAll(eq("an"), eq("pe"), any(Pageable.class))).thenReturn(
      new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

    mockMvc.perform(get("/api/users")
      .param("name", "an")
      .param("surname", "pe")
      .param("sort", "surname,desc")
      .param("page", "2")
      .param("size", "5")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(userService).getAll(eq("an"), eq("pe"), pageable.capture());
    assertAll(
      () -> assertEquals(2, pageable.getValue().getPageNumber()),
      () -> assertEquals(5, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("surname"), Sort.Order.asc("id")),
        pageable.getValue().getSort()));
  }

  @Test
  void getAllCapsThePageSizeAt100() throws Exception {
    when(userService.getAll(isNull(), isNull(), any(Pageable.class))).thenReturn(
      new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

    mockMvc.perform(get("/api/users").param("size", "1000")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(userService).getAll(isNull(), isNull(), pageable.capture());
    assertEquals(100, pageable.getValue().getPageSize());
  }

  @Test
  void getAllRejectsUnsupportedSortWith400() throws Exception {
    mockMvc.perform(get("/api/users").param("sort", "password,asc")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value(containsString("Allowed properties")),
      content().string(not(containsString("password"))));

    verifyNoInteractions(userService);
  }

  @Test
  void updateReturns200WithUpdatedUser() throws Exception {
    when(userService.update(eq(1L), any(UserRequest.class))).thenReturn(user(1, true));

    mockMvc.perform(put("/api/users/1")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_USER_JSON)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(1),
      jsonPath("$.surname").value("Petrova"));
  }

  @Test
  void updateRejectsInvalidBodyWith400() throws Exception {
    String body = """
      {"name": "Anna", "surname": " ", "birthDate": "1990-05-17", "email": "anna@example.com"}
      """;

    mockMvc.perform(put("/api/users/1")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors.length()").value(1),
      jsonPath("$.errors[0].field").value("surname"));

    verifyNoInteractions(userService);
  }

  @ParameterizedTest
  @CsvSource({"activate, true", "deactivate, false"})
  void setActiveUsesTheMatchingFlag(String action, boolean active) throws Exception {
    when(userService.setActive(5L, active)).thenReturn(user(5, active));

    mockMvc.perform(patch("/api/users/5/{action}", action)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(5),
      jsonPath("$.active").value(active));

    verify(userService).setActive(5L, active);
  }

  @Test
  void deleteReturns204WithoutBody() throws Exception {
    mockMvc.perform(delete("/api/users/5")).andExpectAll(
      status().isNoContent(),
      content().string(""));

    verify(userService).delete(5L);
  }

  @Test
  void deleteAnswers404ForUnknownUser() throws Exception {
    doThrow(new UserNotFoundException(99L)).when(userService).delete(99L);

    mockMvc.perform(delete("/api/users/99")).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("User with id 99 not found"));
  }
}
