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

import com.grechanikovars.userservice.config.ClockConfig;
import com.grechanikovars.userservice.config.JwtConfig;
import com.grechanikovars.userservice.config.SecurityConfig;
import com.grechanikovars.userservice.dto.request.CardRequest;
import com.grechanikovars.userservice.dto.response.CardResponse;
import com.grechanikovars.userservice.exception.CardLimitExceededException;
import com.grechanikovars.userservice.exception.CardNumberAlreadyExistsException;
import com.grechanikovars.userservice.exception.PaymentCardNotFoundException;
import com.grechanikovars.userservice.exception.UserNotFoundException;
import com.grechanikovars.userservice.service.PaymentCardService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests of {@link PaymentCardController}: paths, status codes, headers and JSON
 * shape. The service is a mock, so these tests do not touch a database.
 *
 * <p>The security chain is real and every request is made by an admin: who may call which endpoint
 * is tested in {@code SecurityConfigTest}.
 */
@WebMvcTest(controllers = PaymentCardController.class,
  properties = "app.internal.api-key=test-internal-key",
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
@WithMockUser(roles = "ADMIN")
class PaymentCardControllerTest {

  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String VALID_CARD_JSON = """
    {
      "number": "4000000000000001",
      "holder": "ANNA PETROVA",
      "expirationDate": "2099-12-31"
    }
    """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentCardService paymentCardService;

  private static CardResponse card(long id, long userId, boolean active) {
    return new CardResponse(id, userId, "************0001", "ANNA PETROVA",
      LocalDate.of(2099, 12, 31), active, NOW, NOW);
  }

  @Test
  void createReturns201WithLocationUnderCardsAndMaskedNumber() throws Exception {
    when(paymentCardService.create(eq(5L), any(CardRequest.class))).thenReturn(card(10, 5, true));

    mockMvc.perform(post("/api/users/5/cards")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_CARD_JSON)).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION, "http://localhost/api/cards/10"),
      jsonPath("$.id").value(10),
      jsonPath("$.userId").value(5),
      jsonPath("$.maskedNumber").value("************0001"),
      jsonPath("$.number").doesNotExist());

    ArgumentCaptor<CardRequest> request = ArgumentCaptor.forClass(CardRequest.class);
    verify(paymentCardService).create(eq(5L), request.capture());
    assertEquals(new CardRequest("4000000000000001", "ANNA PETROVA", LocalDate.of(2099, 12, 31)),
      request.getValue());
  }

  @Test
  void createRejectsInvalidBodyWith400WithoutEchoingTheNumber() throws Exception {
    String body = """
      {"number": "not-a-number", "holder": "", "expirationDate": "2000-01-01"}
      """;

    mockMvc.perform(post("/api/users/5/cards")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors.length()").value(3),
      jsonPath("$.errors[0].field").value("expirationDate"),
      jsonPath("$.errors[1].field").value("holder"),
      jsonPath("$.errors[2].field").value("number"),
      content().string(not(containsString("not-a-number"))));

    verifyNoInteractions(paymentCardService);
  }

  @Test
  void createAnswers404ForUnknownUser() throws Exception {
    when(paymentCardService.create(eq(99L), any(CardRequest.class)))
      .thenThrow(new UserNotFoundException(99L));

    mockMvc.perform(post("/api/users/99/cards")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_CARD_JSON)).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("User with id 99 not found"));
  }

  @Test
  void createAnswers409WhenTheCardLimitIsReached() throws Exception {
    when(paymentCardService.create(eq(5L), any(CardRequest.class)))
      .thenThrow(new CardLimitExceededException(5L, 5));

    mockMvc.perform(post("/api/users/5/cards")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_CARD_JSON)).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(409));
  }

  @Test
  void createAnswers409ForDuplicateNumber() throws Exception {
    when(paymentCardService.create(eq(5L), any(CardRequest.class)))
      .thenThrow(new CardNumberAlreadyExistsException());

    mockMvc.perform(post("/api/users/5/cards")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_CARD_JSON)).andExpect(status().isConflict());
  }

  @Test
  void getAllByUserIdReturnsPlainList() throws Exception {
    when(paymentCardService.getAllByUserId(5L)).thenReturn(List.of(card(10, 5, true), card(11, 5, false)));

    mockMvc.perform(get("/api/users/5/cards")).andExpectAll(
      status().isOk(),
      jsonPath("$.length()").value(2),
      jsonPath("$[0].id").value(10),
      jsonPath("$[1].active").value(false));
  }

  @Test
  void getAllByUserIdAnswers404ForUnknownUser() throws Exception {
    when(paymentCardService.getAllByUserId(99L)).thenThrow(new UserNotFoundException(99L));

    mockMvc.perform(get("/api/users/99/cards")).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("User with id 99 not found"));
  }

  @Test
  void getByIdReturnsCard() throws Exception {
    when(paymentCardService.getById(10L)).thenReturn(card(10, 5, true));

    mockMvc.perform(get("/api/cards/10")).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(10),
      jsonPath("$.maskedNumber").value("************0001"));
  }

  @Test
  void getByIdAnswers404ForUnknownCard() throws Exception {
    when(paymentCardService.getById(7L)).thenThrow(new PaymentCardNotFoundException(7L));

    mockMvc.perform(get("/api/cards/7")).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("Payment card with id 7 not found"));
  }

  @Test
  void getByIdAnswers400ForNonNumericId() throws Exception {
    mockMvc.perform(get("/api/cards/abc")).andExpect(status().isBadRequest());

    verifyNoInteractions(paymentCardService);
  }

  @Test
  void getAllUsesDefaultPagingAndWrapsThePage() throws Exception {
    when(paymentCardService.getAll(isNull(), isNull(), any(Pageable.class))).thenReturn(
      new PageImpl<>(List.of(card(10, 5, true)), PageRequest.of(0, 20), 1));

    mockMvc.perform(get("/api/cards")).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(1),
      jsonPath("$.content[0].id").value(10),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(1),
      jsonPath("$.totalPages").value(1));

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(paymentCardService).getAll(isNull(), isNull(), pageable.capture());
    assertAll(
      () -> assertEquals(20, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by("id"), pageable.getValue().getSort()));
  }

  @Test
  void getAllPassesOwnerFiltersAndSortWithIdTiebreaker() throws Exception {
    when(paymentCardService.getAll(eq("an"), eq("pe"), any(Pageable.class))).thenReturn(
      new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

    mockMvc.perform(get("/api/cards")
      .param("name", "an")
      .param("surname", "pe")
      .param("sort", "expirationDate,desc")
      .param("page", "1")
      .param("size", "5")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(paymentCardService).getAll(eq("an"), eq("pe"), pageable.capture());
    assertAll(
      () -> assertEquals(1, pageable.getValue().getPageNumber()),
      () -> assertEquals(5, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("expirationDate"), Sort.Order.asc("id")),
        pageable.getValue().getSort()));
  }

  @Test
  void getAllRejectsSortingByCardNumberWith400() throws Exception {
    mockMvc.perform(get("/api/cards").param("sort", "number")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value(containsString("Allowed properties")),
      content().string(not(containsString("number"))));

    verifyNoInteractions(paymentCardService);
  }

  @Test
  void updateReturns200WithUpdatedCard() throws Exception {
    when(paymentCardService.update(eq(10L), any(CardRequest.class))).thenReturn(card(10, 5, true));

    mockMvc.perform(put("/api/cards/10")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_CARD_JSON)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(10));
  }

  @Test
  void updateRejectsInvalidBodyWith400() throws Exception {
    String body = """
      {"number": "4000000000000001", "holder": " ", "expirationDate": "2099-12-31"}
      """;

    mockMvc.perform(put("/api/cards/10")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors.length()").value(1),
      jsonPath("$.errors[0].field").value("holder"));

    verifyNoInteractions(paymentCardService);
  }

  @ParameterizedTest
  @CsvSource({"activate, true", "deactivate, false"})
  void setActiveUsesTheMatchingFlag(String action, boolean active) throws Exception {
    when(paymentCardService.setActive(10L, active)).thenReturn(card(10, 5, active));

    mockMvc.perform(patch("/api/cards/10/{action}", action)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(10),
      jsonPath("$.active").value(active));

    verify(paymentCardService).setActive(10L, active);
  }

  @Test
  void deleteReturns204WithoutBody() throws Exception {
    mockMvc.perform(delete("/api/cards/10")).andExpectAll(
      status().isNoContent(),
      content().string(""));

    verify(paymentCardService).delete(10L);
  }

  @Test
  void deleteAnswers404ForUnknownCard() throws Exception {
    doThrow(new PaymentCardNotFoundException(7L)).when(paymentCardService).delete(7L);

    mockMvc.perform(delete("/api/cards/7")).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("Payment card with id 7 not found"));
  }
}
