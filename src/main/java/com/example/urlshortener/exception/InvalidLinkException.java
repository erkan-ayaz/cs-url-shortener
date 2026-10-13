package com.example.urlshortener.exception;

public class InvalidLinkException extends RuntimeException {

  public InvalidLinkException(String message) {
    super(message);
  }
}
