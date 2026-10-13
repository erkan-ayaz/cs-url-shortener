package com.example.urlshortener.web;

import com.example.urlshortener.api.AnalyticsResponse;
import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.service.LinkService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

  private final LinkService linkService;

  public LinkController(LinkService linkService) {
    this.linkService = linkService;
  }

  @PostMapping
  public ResponseEntity<LinkResponse> createLink(@Valid @RequestBody CreateLinkRequest request) {
    LinkResponse response = linkService.createLink(request);
    return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
  }

  @GetMapping("/{code}/analytics")
  public AnalyticsResponse getAnalytics(
      @PathVariable String code,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
    return linkService.getAnalytics(code, from, to);
  }
}
