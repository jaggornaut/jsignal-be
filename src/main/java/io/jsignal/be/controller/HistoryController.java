package io.jsignal.be.controller;

import io.jsignal.be.dto.HistoryDtos.DomainsResponse;
import io.jsignal.be.dto.HistoryDtos.PositionsResponse;
import io.jsignal.be.dto.HistoryDtos.RangeResponse;
import io.jsignal.be.dto.HistoryDtos.TracksResponse;
import io.jsignal.be.service.HistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HistoryController {

    private final HistoryService service;

    public HistoryController(HistoryService service) {
        this.service = service;
    }

    @GetMapping("/domains")
    public DomainsResponse domains() {
        return new DomainsResponse(service.domains());
    }

    @GetMapping("/{domain}/range")
    public ResponseEntity<RangeResponse> range(@PathVariable String domain) {
        return service.range(domain)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{domain}/positions")
    public PositionsResponse positions(
            @PathVariable String domain,
            @RequestParam("from_ms") long fromMs,
            @RequestParam("to_ms") long toMs,
            @RequestParam(value = "track_key", required = false) String trackKey,
            @RequestParam(value = "interval_s", required = false) Integer intervalS) {
        return new PositionsResponse(service.positions(domain, fromMs, toMs, trackKey, intervalS));
    }

    @GetMapping("/{domain}/tracks")
    public TracksResponse tracks(
            @PathVariable String domain,
            @RequestParam("from_ms") long fromMs,
            @RequestParam("to_ms") long toMs) {
        return new TracksResponse(service.tracks(domain, fromMs, toMs));
    }
}
