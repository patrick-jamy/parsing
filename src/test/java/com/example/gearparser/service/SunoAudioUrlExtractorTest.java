package com.example.gearparser.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SunoAudioUrlExtractorTest {

    private final SunoAudioUrlExtractor extractor = new SunoAudioUrlExtractor();

    @Test
    void extractsEscapedAudioUrlFromPageContent() {
        String content = "...audio_url\\\":\\\"https:\\/\\/cdn1.suno.ai\\/26215c0e-b8ca-489f-88ac-99f023b1708e.mp3\"...";

        String result = extractor.extractMp3Url(content).orElseThrow();

        assertEquals("https://cdn1.suno.ai/26215c0e-b8ca-489f-88ac-99f023b1708e.mp3", result);
    }

    @Test
    void returnsEmptyWhenNoAudioUrl() {
        assertTrue(extractor.extractMp3Url("<html>nope</html>").isEmpty());
    }
}
