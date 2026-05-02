package com.example.gearparser.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a direct Suno MP3 URL from page/JSON content.
 */
public class SunoAudioUrlExtractor {

    private static final Pattern AUDIO_URL_PATTERN = Pattern.compile(
            "audio_url\\\\\":\\\\\"(https?:\\\\/\\\\/[^\"\\\\]+\\\\.mp3)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Finds and unescapes the first audio_url mp3 link.
     *
     * @param content raw HTML/JSON content from Suno
     * @return optional direct mp3 URL
     */
    public Optional<String> extractMp3Url(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = AUDIO_URL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String rawEscaped = matcher.group(1);
        String unescaped = rawEscaped.replace("\\\\/", "/");
        return Optional.of(unescaped);
    }
}
