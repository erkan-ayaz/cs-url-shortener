package com.example.urlshortener.web;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.exception.CodeGenerationException;
import com.example.urlshortener.exception.InvalidLinkException;
import com.example.urlshortener.exception.LinkExpiredException;
import com.example.urlshortener.exception.LinkNotFoundException;
import com.example.urlshortener.service.ShortenerMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final Pattern SHORT_CODE_PATH = Pattern.compile("/[A-Za-z0-9]{8}");

  private final ShortenerMetrics shortenerMetrics;

  public ApiExceptionHandler(ShortenerMetrics shortenerMetrics) {
    this.shortenerMetrics = shortenerMetrics;
  }

  @ExceptionHandler(LinkNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(
      LinkNotFoundException exception, HttpServletRequest request) {
    logRedirectError(HttpStatus.NOT_FOUND, exception, request);
    return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
  }

  @ExceptionHandler(LinkExpiredException.class)
  public ResponseEntity<ApiError> handleExpired(
      LinkExpiredException exception, HttpServletRequest request) {
    logRedirectError(HttpStatus.GONE, exception, request);
    return error(HttpStatus.GONE, exception.getMessage(), request);
  }

  @ExceptionHandler({
    InvalidLinkException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiError> handleBadRequest(
      Exception exception, HttpServletRequest request) {
    String message =
        exception instanceof InvalidLinkException
            ? exception.getMessage()
            : "The request is invalid";
    logRedirectError(HttpStatus.BAD_REQUEST, exception, request);
    return error(HttpStatus.BAD_REQUEST, message, request);
  }

  @ExceptionHandler({DataAccessException.class, CodeGenerationException.class})
  public ResponseEntity<ApiError> handleUnavailable(
      Exception exception, HttpServletRequest request) {
    logRedirectError(HttpStatus.SERVICE_UNAVAILABLE, exception, request);
    return error(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable", request);
  }

  private void logRedirectError(
      HttpStatus status, Exception exception, HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    if (!("GET".equals(method) || "HEAD".equals(method))
        || !SHORT_CODE_PATH.matcher(path).matches()) {
      return;
    }

    shortenerMetrics.redirectFailed(status);
    if (status.is5xxServerError()) {
      LOGGER.warn(
          "Redirect failed status={} path={} cause={}",
          status.value(),
          path,
          exception.getClass().getSimpleName());
    } else {
      LOGGER.info("Redirect rejected status={} path={}", status.value(), path);
    }
  }

  private ResponseEntity<ApiError> error(
      HttpStatus status, String message, HttpServletRequest request) {
    ApiError body =
        new ApiError(
            Instant.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            request.getRequestURI());
    return ResponseEntity.status(status).body(body);
  }
}
