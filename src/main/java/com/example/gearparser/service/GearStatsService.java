package com.example.gearparser.service;

import com.example.gearparser.model.ParseResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class GearStatsService {

    private final HtmlFetcher htmlFetcher;
    private final GearParsingEngine parsingEngine;

    @Value("${app.source-url:https://swgoh.gg/units/ahsoka-tano-fulcrum/gear/}")
    private String sourceUrl;

    private volatile ParseResponse cache;

    public GearStatsService(HtmlFetcher htmlFetcher, GearParsingEngine parsingEngine) {
        this.htmlFetcher = htmlFetcher;
        this.parsingEngine = parsingEngine;
    }

    public ParseResponse getLatest() {
        if (cache == null) {
            return reload();
        }
        return cache;
    }

    public synchronized ParseResponse reload() {
        String html;
        String strategyPrefix = "remote";
        try {
            html = htmlFetcher.fetch(sourceUrl);
        } catch (Exception ex) {
            html = loadFallbackHtml();
            strategyPrefix = "fallback-sample";
        }

        GearParsingEngine.ParseResult result = parsingEngine.parse(html);
        cache = new ParseResponse(sourceUrl, Instant.now(), result.stats(), strategyPrefix + "+" + result.strategy());
        return cache;
    }

    private String loadFallbackHtml() {
        try {
            return new String(new ClassPathResource("sample-gear-page.html").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger le HTML de fallback local.", e);
        }
    }
}
