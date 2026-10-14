package com.example.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerLoadTest {

  private static final int WORKER_COUNT = 8;
  private static final int OPERATIONS_PER_WORKER = 12;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration TEST_DEADLINE = Duration.ofSeconds(20);

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void handlesConcurrentCreateAndRedirectRequestsWithinDeadline() throws Exception {
    RestTemplate restTemplate = createRestTemplate();
    String seedCode = createLink(restTemplate, "seed", "initial");
    ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
    CountDownLatch workersReady = new CountDownLatch(WORKER_COUNT);
    CountDownLatch startWorkers = new CountDownLatch(1);
    List<Future<Integer>> workerResults = new ArrayList<>();

    for (int worker = 0; worker < WORKER_COUNT; worker++) {
      int workerId = worker;
      workerResults.add(
          executor.submit(
              () -> {
                workersReady.countDown();
                if (!startWorkers.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Load-test workers did not start together");
                }

                for (int operation = 0; operation < OPERATIONS_PER_WORKER; operation++) {
                  if ((workerId + operation) % 3 == 0) {
                    createLink(
                        restTemplate, Integer.toString(workerId), Integer.toString(operation));
                  } else {
                    assertRedirect(restTemplate, seedCode);
                  }
                }
                return OPERATIONS_PER_WORKER;
              }));
    }

    try {
      assertTrue(workersReady.await(5, TimeUnit.SECONDS), "Workers did not become ready");
      long startedAt = System.nanoTime();
      long deadline = startedAt + TEST_DEADLINE.toNanos();
      startWorkers.countDown();

      int completedOperations = 0;
      for (Future<Integer> workerResult : workerResults) {
        long remainingNanos = deadline - System.nanoTime();
        assertTrue(remainingNanos > 0, "Concurrent requests exceeded the test deadline");
        completedOperations += workerResult.get(remainingNanos, TimeUnit.NANOSECONDS);
      }

      long elapsedNanos = System.nanoTime() - startedAt;
      assertEquals(WORKER_COUNT * OPERATIONS_PER_WORKER, completedOperations);
      assertTrue(
          elapsedNanos <= TEST_DEADLINE.toNanos(),
          "Concurrent requests exceeded the test deadline");
    } finally {
      startWorkers.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Load-test workers did not stop");
    }
  }

  private RestTemplate createRestTemplate() {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(REQUEST_TIMEOUT);
    return new RestTemplate(requestFactory);
  }

  private String createLink(RestTemplate restTemplate, String worker, String operation)
      throws Exception {
    String destination = "https://example.com/load/" + worker + "/" + operation;
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("url", destination), headers);

    var response = restTemplate.postForEntity(baseUrl() + "/api/v1/links", request, String.class);
    assertEquals(HttpStatus.CREATED, response.getStatusCode(), "Link creation failed");

    JsonNode body = objectMapper.readTree(response.getBody());
    String code = body.path("code").asText();
    assertTrue(code.matches("[0-9A-Za-z]{8}"), "API returned an invalid short code");
    return code;
  }

  private void assertRedirect(RestTemplate restTemplate, String code) {
    var response =
        restTemplate.exchange(baseUrl() + "/" + code, HttpMethod.GET, null, String.class);
    assertEquals(HttpStatus.FOUND, response.getStatusCode(), "Redirect did not return 302");
    assertEquals(
        URI.create("https://example.com/load/seed/initial"),
        response.getHeaders().getLocation(),
        "Redirect Location did not match the seed destination");
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }
}
