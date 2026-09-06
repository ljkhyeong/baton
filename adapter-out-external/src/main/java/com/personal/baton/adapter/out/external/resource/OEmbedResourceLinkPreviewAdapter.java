package com.personal.baton.adapter.out.external.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.personal.baton.application.workspace.port.in.ResourceLinkPreviewUseCase.Preview;
import com.personal.baton.application.workspace.port.out.ResourceLinkPreviewPort;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.ResourceThumbnail;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OEmbedResourceLinkPreviewAdapter implements ResourceLinkPreviewPort {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be");
    private static final Set<String> VIMEO_HOSTS = Set.of("vimeo.com", "www.vimeo.com", "player.vimeo.com");
    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Semaphore requests = new Semaphore(4);

    @Autowired
    public OEmbedResourceLinkPreviewAdapter(RestClient.Builder builder) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.requestFactory(factory).build();
    }

    OEmbedResourceLinkPreviewAdapter(RestClient client) {
        this.client = client;
    }

    @Override
    public Preview preview(String url) {
        URI endpoint = endpoint(url);
        if (endpoint == null || !requests.tryAcquire()) {
            return Preview.unavailable();
        }
        try {
            return client.get().uri(endpoint).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    return Preview.unavailable();
                }
                byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    return Preview.unavailable();
                }
                OEmbed data = mapper.readValue(body, OEmbed.class);
                if (data == null || data.title() == null || data.title().isBlank()) {
                    return Preview.unavailable();
                }
                String title = data.title().strip();
                String thumbnail;
                try {
                    thumbnail = ResourceThumbnail.normalize(data.thumbnailUrl());
                } catch (DomainValidationException exception) {
                    thumbnail = null;
                }
                return new Preview(title.substring(0, Math.min(title.length(), 200)), thumbnail);
            });
        } catch (RestClientException | JacksonException exception) {
            return Preview.unavailable();
        } finally {
            requests.release();
        }
    }

    private static URI endpoint(String url) {
        if (url == null || url.length() > 2048) return null;
        try {
            URI uri = URI.create(url.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            String canonical;
            String api;
            if (YOUTUBE_HOSTS.contains(host)) {
                String id;
                if (host.equals("youtu.be")) {
                    id = path.substring(1);
                } else if (path.equals("/watch")) {
                    id = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("v");
                } else if (path.startsWith("/shorts/") || path.startsWith("/embed/")) {
                    id = path.substring(path.lastIndexOf('/') + 1);
                } else return null;
                if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return null;
                canonical = "https://www.youtube.com/watch?v=" + id;
                api = "https://www.youtube.com/oembed";
            } else if (VIMEO_HOSTS.contains(host)) {
                if (!path.matches("/(?:video/)?[0-9]{1,12}")) return null;
                canonical = "https://vimeo.com/" + path.substring(path.lastIndexOf('/') + 1);
                api = "https://vimeo.com/api/oembed.json";
            } else return null;
            return UriComponentsBuilder.fromUriString(api).queryParam("url", "{url}")
                    .queryParam("format", "json").encode().buildAndExpand(canonical).toUri();
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OEmbed(String title, @JsonProperty("thumbnail_url") String thumbnailUrl) {
    }
}
