package com.example.urlshortener.service;

import com.example.urlshortener.api.AnalyticsResponse;
import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.api.LinkResponse;
import java.net.URI;
import java.time.OffsetDateTime;

public interface LinkService {

  LinkResponse createLink(CreateLinkRequest request);

  URI resolveRedirect(String code);

  AnalyticsResponse getAnalytics(String code, OffsetDateTime from, OffsetDateTime to);
}
