package com.example.urlshortener.service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public class ShortCodeGenerator {

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int CODE_LENGTH = 8;
  private static final BigInteger CODE_SPACE =
      BigInteger.valueOf(ALPHABET.length()).pow(CODE_LENGTH);
  private final SecureRandom secureRandom = new SecureRandom();

  public String generate() {
    BigInteger value;
    do {
      value = new BigInteger(48, secureRandom);
    } while (value.compareTo(CODE_SPACE) >= 0);

    return encode(value);
  }

  private String encode(BigInteger value) {
    char[] code = new char[CODE_LENGTH];
    Arrays.fill(code, ALPHABET.charAt(0));
    BigInteger radix = BigInteger.valueOf(ALPHABET.length());
    int index = CODE_LENGTH - 1;

    while (value.signum() > 0) {
      BigInteger[] division = value.divideAndRemainder(radix);
      code[index--] = ALPHABET.charAt(division[1].intValueExact());
      value = division[0];
    }

    return new String(code);
  }
}
