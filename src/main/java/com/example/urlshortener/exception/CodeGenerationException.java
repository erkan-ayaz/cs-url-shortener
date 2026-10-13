package com.example.urlshortener.exception;

public class CodeGenerationException extends RuntimeException {

  public CodeGenerationException() {
    super("Unable to allocate a unique short code after several attempts");
  }
}
