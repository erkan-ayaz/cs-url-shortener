package com.example.urlshortener.service;

import com.example.urlshortener.api.AnalyticsResponse;
import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.config.LinkProperties;
import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.exception.CodeGenerationException;
import com.example.urlshortener.exception.InvalidLinkException;
import com.example.urlshortener.exception.LinkExpiredException;
import com.example.urlshortener.exception.LinkNotFoundException;
import com.example.urlshortener.repository.LinkRepository;
import com.example.urlshortener.repository.RedirectEventRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkServiceImpl implements LinkService {

  private static final Logger LOGGER = LoggerFactory.getLogger(LinkServiceImpl.class);
  private static final int MAX_CODE_ATTEMPTS = 5;

  private final LinkRepository linkRepository;
  private final RedirectEventRepository redirectEventRepository;
  private final ShortCodeGenerator shortCodeGenerator;
  private final RedirectEventWriter redirectEventWriter;
  private final ShortenerMetrics shortenerMetrics;
  private final LinkProperties linkProperties;
  private final Clock clock;

  public LinkServiceImpl(
      LinkRepository linkRepository,
      RedirectEventRepository redirectEventRepository,
      ShortCodeGenerator shortCodeGenerator,
      RedirectEventWriter redirectEventWriter,
      ShortenerMetrics shortenerMetrics,
      LinkProperties linkProperties,
      Clock clock) {
    this.linkRepository = linkRepository;
    this.redirectEventRepository = redirectEventRepository;
    this.shortCodeGenerator = shortCodeGenerator;
    this.redirectEventWriter = redirectEventWriter;
    this.shortenerMetrics = shortenerMetrics;
    this.linkProperties = linkProperties;
    this.clock = clock;
  }

  @Override
  public LinkResponse createLink(CreateLinkRequest request) {
    String destinationUrl = validateDestination(request.url());
    Instant createdAt = clock.instant();
    Instant expiresAt = resolveExpiration(request.expiresAt(), createdAt);

    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = shortCodeGenerator.generate();
      if (linkRepository.existsByCode(code)) {
        continue;
      }

      LinkEntity link = new LinkEntity(code, destinationUrl, createdAt, expiresAt);
      try {
        LinkEntity savedLink = linkRepository.saveAndFlush(link);
        LOGGER.info("Created short link code={} expiresAt={}", code, expiresAt);
        shortenerMetrics.linkCreated();
        return toResponse(savedLink);
      } catch (DataIntegrityViolationException exception) {
        if (!linkRepository.existsById(code)) {
          throw exception;
        }
      }
    }

    throw new CodeGenerationException();
  }

  @Override
  @Transactional(readOnly = true, timeout = 2)
  public URI resolveRedirect(String code) {
    LinkEntity link =
        linkRepository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    Instant now = clock.instant();

    if (link.getDisabledAt() != null) {
      throw new LinkNotFoundException(code);
    }
    if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now)) {
      throw new LinkExpiredException(code);
    }

    try {
      redirectEventWriter.record(code, now);
    } catch (DataAccessException exception) {
      LOGGER.warn("Unable to record redirect analytics for code {}", code, exception);
    }

    shortenerMetrics.redirectResolved();
    LOGGER.trace("Redirect resolved status=302 code={}", code);
    return URI.create(link.getDestinationUrl());
  }

  @Override
  @Transactional(readOnly = true, timeout = 2)
  public AnalyticsResponse getAnalytics(String code, OffsetDateTime from, OffsetDateTime to) {
    if (!from.isBefore(to)) {
      throw new InvalidLinkException("The analytics start must be before the end");
    }

    Duration range = Duration.between(from, to);
    if (range.compareTo(linkProperties.maxAnalyticsRange()) > 0) {
      throw new InvalidLinkException("The analytics range exceeds the configured maximum");
    }

    if (!linkRepository.existsByCode(code)) {
      throw new LinkNotFoundException(code);
    }

    Instant start = from.toInstant();
    Instant end = to.toInstant();
    long count =
        redirectEventRepository.countByLinkCodeAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            code, start, end);
    return new AnalyticsResponse(code, start, end, count);
  }

  private String validateDestination(String destinationUrl) {
    if (destinationUrl.length() > linkProperties.maxUrlLength()) {
      throw new InvalidLinkException("The destination URL exceeds the maximum length");
    }

    URI uri;
    try {
      uri = URI.create(destinationUrl);
    } catch (IllegalArgumentException exception) {
      throw new InvalidLinkException("The destination URL is not valid");
    }

    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new InvalidLinkException(
          "Only absolute HTTP and HTTPS URLs without credentials are allowed");
    }

    return destinationUrl;
  }

  private Instant resolveExpiration(OffsetDateTime requestedExpiration, Instant now) {
    Instant expiresAt =
        requestedExpiration == null
            ? (linkProperties.defaultTtl() == null ? null : now.plus(linkProperties.defaultTtl()))
            : requestedExpiration.toInstant();

    if (expiresAt == null) {
      return null;
    }
    if (!expiresAt.isAfter(now)) {
      throw new InvalidLinkException("Expiration must be in the future");
    }
    if (Duration.between(now, expiresAt).compareTo(linkProperties.maxTtl()) > 0) {
      throw new InvalidLinkException("Expiration exceeds the configured maximum lifetime");
    }
    return expiresAt;
  }

  private LinkResponse toResponse(LinkEntity link) {
    String baseUrl = linkProperties.publicBaseUrl().replaceAll("/+$", "");
    return new LinkResponse(
        link.getCode(),
        baseUrl + "/" + link.getCode(),
        link.getDestinationUrl(),
        link.getCreatedAt(),
        link.getExpiresAt());
  }
}
