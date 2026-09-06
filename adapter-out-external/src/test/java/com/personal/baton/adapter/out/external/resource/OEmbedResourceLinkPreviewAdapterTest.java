package com.personal.baton.adapter.out.external.resource;

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@Tag("policy")
class OEmbedResourceLinkPreviewAdapterTest {
    private MockRestServiceServer server;
    private OEmbedResourceLinkPreviewAdapter adapter;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new OEmbedResourceLinkPreviewAdapter(builder.build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://youtu.be/dQw4w9WgXcQ?si=tracking", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgXcQ"})
    @DisplayName("YouTube 링크는 추적 정보를 제거하고 고정 oEmbed API에서 제목과 이미지만 읽는다")
    void readsYoutube(String url) {
        server.expect(requestTo(URI.create("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ&format=json")))
                .andRespond(withSuccess("""
                        {"title":" 소개 영상 ","thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","html":"<iframe src='https://untrusted.test'></iframe>"}
                        """, MediaType.APPLICATION_JSON));
        var result = adapter.preview(url);
        assertThat(result.title()).isEqualTo("소개 영상");
        assertThat(result.thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        server.verify();
    }

    @Test @DisplayName("Vimeo 플레이어 링크는 영상 번호만 공식 API에 전달한다")
    void readsVimeo() {
        server.expect(requestTo(URI.create("https://vimeo.com/api/oembed.json?url=https%3A%2F%2Fvimeo.com%2F76979871&format=json")))
                .andRespond(withSuccess("""
                        {"title":"소개 영상","thumbnail_url":"https://i.vimeocdn.com/video/123.jpg"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(adapter.preview("https://player.vimeo.com/video/76979871?autoplay=1").thumbnailUrl())
                .isEqualTo("https://i.vimeocdn.com/video/123.jpg");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/video", "http://127.0.0.1", "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
            "https://user:password@youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com:8080/watch?v=dQw4w9WgXcQ",
            "https://youtu.be", "https://www.youtube.com/watch?v=invalid", "https://vimeo.com/channels/staffpicks/123"})
    @DisplayName("지원하지 않거나 모호한 링크에는 외부 요청을 보내지 않는다")
    void doesNotFetchArbitraryUrls(String url) {
        assertThat(adapter.preview(url).title()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 404, 429, 500})
    @DisplayName("리다이렉트와 외부 오류는 수동 등록으로 전환한다")
    void fallsBackOnStatus(int status) {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.valueOf(status)).header("Location", "http://127.0.0.1/"));
        assertThat(adapter.preview("https://youtu.be/dQw4w9WgXcQ").title()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid json", "null", "{}", "{}"})
    @DisplayName("잘못된 외부 응답은 자료 등록 오류로 전파하지 않는다")
    void fallsBackOnMalformedBody(String body) {
        server.expect(request -> {}).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(adapter.preview("https://youtu.be/dQw4w9WgXcQ").title()).isNull();
    }

    @Test @DisplayName("허용 범위 밖의 이미지 주소는 제목만 사용한다")
    void discardsUnsafeThumbnail() {
        server.expect(request -> {}).andRespond(withSuccess("""
                {"title":"영상","thumbnail_url":"http://127.0.0.1/image"}
                """, MediaType.APPLICATION_JSON));
        var result = adapter.preview("https://youtu.be/dQw4w9WgXcQ");
        assertThat(result.title()).isEqualTo("영상");
        assertThat(result.thumbnailUrl()).isNull();
    }

    @Test @DisplayName("32KiB를 넘는 응답은 저장하지 않는다")
    void boundsResponse() {
        server.expect(request -> {}).andRespond(withSuccess("a".repeat(33 * 1024), MediaType.APPLICATION_JSON));
        assertThat(adapter.preview("https://youtu.be/dQw4w9WgXcQ").title()).isNull();
    }
}
