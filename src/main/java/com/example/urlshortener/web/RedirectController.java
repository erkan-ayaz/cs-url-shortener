package com.example.urlshortener.web;

import com.example.urlshortener.service.LinkService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

  private final LinkService linkService;

  public RedirectController(LinkService linkService) {
    this.linkService = linkService;
  }

  @RequestMapping(
      path = "/{code}",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  public ResponseEntity<Void> redirect(@PathVariable String code) {
    URI destination = linkService.resolveRedirect(code);
    return ResponseEntity.status(HttpStatus.FOUND).location(destination).build();
  }
}
