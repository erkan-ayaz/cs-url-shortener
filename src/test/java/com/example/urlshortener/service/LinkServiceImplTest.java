package com.example.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.config.LinkProperties;
import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.exception.CodeGenerationException;
import com.example.urlshortener.exception.InvalidLinkException;
import com.example.urlshortener.repository.LinkRepository;
import com.example.urlshortener.repository.RedirectEventRepository;
import com.example.urlshortener.web.ApiExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class LinkServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
  private static final String DESTINATION = "https://example.com/test";

  @Mock private LinkRepository linkRepository;

  @Mock private RedirectEventRepository redirectEventRepository;

  @Mock private ShortCodeGenerator shortCodeGenerator;

  @Mock private RedirectEventWriter redirectEventWriter;

  @Captor private ArgumentCaptor<LinkEntity> linkCaptor;

  private SimpleMeterRegistry meterRegistry;
  private ShortenerMetrics shortenerMetrics;
  private LinkServiceImpl linkService;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    shortenerMetrics = new ShortenerMetrics(meterRegistry);
    LinkProperties properties =
        new LinkProperties(
            "http://localhost:8080",
            Duration.ofDays(30),
            Duration.ofDays(365),
            Duration.ofDays(366),
            2048);
    linkService =
        new LinkServiceImpl(
            linkRepository,
            redirectEventRepository,
            shortCodeGenerator,
            redirectEventWriter,
            shortenerMetrics,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void rejectsAnalyticsRangeAboveConfiguredMaximumBeforeDatabaseReads() {
    OffsetDateTime from = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    Throwable failure =
        catchThrowable(() -> linkService.getAnalytics("Range001", from, from.plusDays(367)));

    assertThat(failure)
        .isInstanceOf(InvalidLinkException.class)
        .hasMessage("The analytics range exceeds the configured maximum");
    verifyNoInteractions(linkRepository, redirectEventRepository);
  }

  @Test
  void retriesAnExistingCodeAndPersistsTheNextUniqueCode() {
    when(shortCodeGenerator.generate()).thenReturn("Taken001", "NewCode1");
    when(linkRepository.existsByCode("Taken001")).thenReturn(true);
    when(linkRepository.existsByCode("NewCode1")).thenReturn(false);
    when(linkRepository.saveAndFlush(any(LinkEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var response = linkService.createLink(new CreateLinkRequest(DESTINATION, null));

    assertThat(response.code()).isEqualTo("NewCode1");
    verify(shortCodeGenerator, times(2)).generate();
    verify(linkRepository).saveAndFlush(linkCaptor.capture());
    assertThat(linkCaptor.getValue().getCode()).isEqualTo("NewCode1");
  }

  @Test
  void failsAfterBoundedCodeCollisionRetries() {
    when(shortCodeGenerator.generate()).thenReturn("Taken001");
    when(linkRepository.existsByCode("Taken001")).thenReturn(true);

    Throwable failure =
        catchThrowable(() -> linkService.createLink(new CreateLinkRequest(DESTINATION, null)));

    assertThat(failure).isInstanceOf(CodeGenerationException.class);
    verify(shortCodeGenerator, times(5)).generate();
    verify(linkRepository, never()).saveAndFlush(any(LinkEntity.class));
  }

  @Test
  void mapsCreateDatabaseFailureToGenericServiceUnavailable() {
    when(shortCodeGenerator.generate()).thenReturn("NewCode1");
    when(linkRepository.existsByCode("NewCode1")).thenReturn(false);
    when(linkRepository.saveAndFlush(any(LinkEntity.class)))
        .thenThrow(new DataAccessResourceFailureException("private database detail"));

    Throwable failure =
        catchThrowable(() -> linkService.createLink(new CreateLinkRequest(DESTINATION, null)));

    assertThat(failure).isInstanceOf(DataAccessException.class);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
    ResponseEntity<ApiError> response =
        new ApiExceptionHandler(shortenerMetrics).handleUnavailable((Exception) failure, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().message()).isEqualTo("The service is temporarily unavailable");
    assertThat(response.getBody().message()).doesNotContain("private database detail");
  }

  @Test
  void mapsRedirectReadFailureToGenericServiceUnavailable() {
    String code = "ReadErr1";
    when(linkRepository.findByCode(code))
        .thenThrow(new DataAccessResourceFailureException("private database detail"));

    Throwable failure = catchThrowable(() -> linkService.resolveRedirect(code));

    assertThat(failure).isInstanceOf(DataAccessException.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + code);
    ResponseEntity<ApiError> response =
        new ApiExceptionHandler(shortenerMetrics).handleUnavailable((Exception) failure, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().message()).isEqualTo("The service is temporarily unavailable");
    assertThat(response.getBody().message()).doesNotContain("private database detail");
    assertThat(
            meterRegistry
                .counter("shortener.redirects", "outcome", "error", "status", "503")
                .count())
        .isEqualTo(1);
  }

  @Test
  void servesRedirectWhenAnalyticsWriteFails() {
    String code = "Write001";
    LinkEntity link =
        new LinkEntity(code, DESTINATION, NOW.minusSeconds(60), NOW.plusSeconds(3600));
    when(linkRepository.findByCode(code)).thenReturn(Optional.of(link));
    doThrow(new DataAccessResourceFailureException("analytics database unavailable"))
        .when(redirectEventWriter)
        .record(eq(code), any(Instant.class));

    URI result = linkService.resolveRedirect(code);

    assertThat(result).isEqualTo(URI.create(DESTINATION));
    assertThat(meterRegistry.counter("shortener.redirects", "outcome", "success").count())
        .isEqualTo(1);
    verify(redirectEventWriter).record(eq(code), any(Instant.class));
  }
}
