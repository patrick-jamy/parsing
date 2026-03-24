package com.example.gearparser.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class HtmlFetcher {

    public String fetch(String url) {
        try {
            Connection connection = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .timeout(20000)
                    .maxBodySize(0)
                    .followRedirects(true);
            return connection.execute().body();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de récupérer la page HTML distante. Vérifiez l'URL et d'éventuels blocages anti-bot.", e);
        }
    }
}
