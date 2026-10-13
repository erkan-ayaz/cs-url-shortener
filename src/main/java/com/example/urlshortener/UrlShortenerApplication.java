package com.example.urlshortener;

import com.example.urlshortener.config.LinkProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(LinkProperties.class)
public class UrlShortenerApplication {

  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
