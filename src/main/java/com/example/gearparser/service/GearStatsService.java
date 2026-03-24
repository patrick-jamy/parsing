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
    private static final int MIN_EXPECTED_REMOTE_GEAR_COUNT = 12;
    private static final int MIN_EXPECTED_FALLBACK_GEAR_COUNT = 1;

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
        String strategyPrefix = "remote";
        GearParsingEngine.ParseResult result;
        try {
            String html = htmlFetcher.fetch(sourceUrl);
            result = parseAndValidate(html, "source distante", MIN_EXPECTED_REMOTE_GEAR_COUNT);
        } catch (Exception ex) {
            strategyPrefix = "fallback-sample";
            String fallbackHtml = loadFallbackHtml();
            result = parseAndValidate(fallbackHtml, "fallback local", MIN_EXPECTED_FALLBACK_GEAR_COUNT);
        }

        cache = new ParseResponse(sourceUrl, Instant.now(), result.stats(), strategyPrefix + "+" + result.strategy());
        return cache;
    }

    private GearParsingEngine.ParseResult parseAndValidate(String html, String sourceLabel, int minExpectedGearCount) {
        GearParsingEngine.ParseResult result = parsingEngine.parse(html);
        if (result.stats().size() < minExpectedGearCount) {
            throw new IllegalStateException(
                    "Parsing incomplet depuis " + sourceLabel + " : " + result.stats().size()
                            + " gears trouvés au lieu d'au moins " + minExpectedGearCount + ".");
        }
        return result;
    }

    private String loadFallbackHtml() {
        try {
            return new String(new ClassPathResource("sample-gear-page.html").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger le HTML de fallback local.", e);
        }
    }
}
