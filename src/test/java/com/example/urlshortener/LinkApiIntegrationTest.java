package com.example.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.repository.LinkRepository;
import com.example.urlshortener.repository.RedirectEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LinkApiIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private LinkRepository linkRepository;

  @Autowired private RedirectEventRepository redirectEventRepository;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void createsRedirectsAndReportsAnalytics(CapturedOutput output) throws Exception {
    double createdBefore = meterRegistry.counter("shortener.links.created").count();
    double redirectsBefore =
        meterRegistry.counter("shortener.redirects", "outcome", "success").count();
    String requestBody =
        objectMapper
            .createObjectNode()
            .put("url", "https://example.com/path")
            .put("expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusDays(60).toString())
            .toString();

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").isString())
            .andExpect(jsonPath("$.short_url").isString())
            .andReturn();

    JsonNode response = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String code = response.get("code").asText();

    mockMvc
        .perform(get("/{code}", code))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/path"));
    assertThat(output).contains("Redirect resolved status=302 code=" + code);
    assertThat(meterRegistry.counter("shortener.links.created").count())
        .isEqualTo(createdBefore + 1);
    assertThat(meterRegistry.counter("shortener.redirects", "outcome", "success").count())
        .isEqualTo(redirectsBefore + 1);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    mockMvc
        .perform(
            get("/api/v1/links/{code}/analytics", code)
                .param("from", now.minusMinutes(1).toString())
                .param("to", now.plusMinutes(1).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.redirect_count").value(1));
  }

  @Test
  void rejectsNonHttpDestinations() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"javascript:alert(1)\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void returnsNotFoundForUnknownCode(CapturedOutput output) throws Exception {
    double errorsBefore =
        meterRegistry.counter("shortener.redirects", "outcome", "error", "status", "404").count();
    mockMvc
        .perform(get("/abcdefgh"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
    assertThat(output).contains("Redirect rejected status=404 path=/abcdefgh");
    assertThat(
            meterRegistry
                .counter("shortener.redirects", "outcome", "error", "status", "404")
                .count())
        .isEqualTo(errorsBefore + 1);
  }

  @Test
  void returnsGoneForExpiredLinkWithoutRecordingRedirect(CapturedOutput output) throws Exception {
    Instant now = Instant.now();
    String code = "Expired1";
    double errorsBefore =
        meterRegistry.counter("shortener.redirects", "outcome", "error", "status", "410").count();
    linkRepository.saveAndFlush(
        new LinkEntity(
            code,
            "https://example.com/expired",
            now.minusSeconds(2 * 24 * 60 * 60),
            now.minusSeconds(24 * 60 * 60)));

    mockMvc
        .perform(get("/{code}", code))
        .andExpect(status().isGone())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.status").value(410))
        .andExpect(jsonPath("$.message").value("Link has expired: " + code));

    long redirectCount =
        redirectEventRepository.countByLinkCodeAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            code, now.minusSeconds(24 * 60 * 60), now.plusSeconds(60));
    assertThat(redirectCount).isZero();
    assertThat(output).contains("Redirect rejected status=410 path=/" + code);
    assertThat(
            meterRegistry
                .counter("shortener.redirects", "outcome", "error", "status", "410")
                .count())
        .isEqualTo(errorsBefore + 1);
  }
}
