package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.adapter.out.external.http.OutboundRestClients;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class BriefRestClientFactoryTest {

    private static final String TOKEN = "brief-service-api-test-token-00000000001";
    private static final String STORE_PASSWORD = "changeit";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000002611");
    private static final UUID SEASON_ID = UUID.fromString("00000000-0000-0000-0000-000000002612");

    @TempDir
    private Path tempDirectory;

    private HttpsServer server;
    private SslBundle trustedBundle;

    @BeforeEach
    void startServerWithPrivateCertificate() throws Exception {
        Path keyStore = tempDirectory.resolve("brief-service.p12");
        keytool("-genkeypair", "-alias", "brief-service", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", STORE_PASSWORD);
        Path certificate = tempDirectory.resolve("brief-service.pem");
        keytool("-exportcert", "-rfc", "-alias", "brief-service", "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", STORE_PASSWORD);
        trustedBundle = SslBundle.of(new PemSslStoreBundle(
                null,
                PemSslStoreDetails.forCertificate(Files.readString(certificate))
        ));

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext(keyStore)));
        server.createContext("/", exchange -> {
            byte[] body = "{\"highCount\":1,\"mediumCount\":2,\"revisionGapCount\":3}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("BRIEF 서비스 클라이언트는 전용 SSL 번들로 사설 인증서를 신뢰한다")
    void trustsPrivateCertificateThroughSslBundle() {
        RestClientBriefServiceClient client = serviceClient(uri("localhost"), trustedBundle);

        assertThat(client.summarizeAttention(TEAM_ID, SEASON_ID).highCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SSL 번들이 없는 클라이언트는 JVM 기본 신뢰 저장소를 유지해 사설 인증서를 거부한다")
    void keepsDefaultTrustStoreWithoutSslBundle() {
        RestClientBriefServiceClient client = serviceClient(uri("localhost"), null);

        assertThatThrownBy(() -> client.summarizeAttention(TEAM_ID, SEASON_ID))
                .isInstanceOf(BriefIntegrationUnavailableException.class);
    }

    @Test
    @DisplayName("전용 SSL 번들을 써도 인증서 호스트 이름 검증은 유지한다")
    void keepsHostnameVerificationWithSslBundle() {
        RestClientBriefServiceClient client = serviceClient(uri("127.0.0.1"), trustedBundle);

        assertThatThrownBy(() -> client.summarizeAttention(TEAM_ID, SEASON_ID))
                .isInstanceOf(BriefIntegrationUnavailableException.class);
    }

    @Test
    @DisplayName("전용 SSL 번들은 서비스 클라이언트에만 적용하고 이벤트 전달 클라이언트에는 전달하지 않는다")
    void appliesSslBundleOnlyToServiceClient() {
        List<HttpClientSettings> appliedSettings = new ArrayList<>();
        ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> requestFactoryBuilder = settings -> {
            appliedSettings.add(settings);
            return new SimpleClientHttpRequestFactory();
        };
        BriefRestClientFactory factory = new BriefRestClientFactory(new OutboundRestClients(
                RestClient.builder(),
                requestFactoryBuilder,
                HttpClientSettings.defaults()
        ));

        factory.createEditionServiceClient(uri("localhost"), TOKEN, CONNECT_TIMEOUT, READ_TIMEOUT, trustedBundle);
        factory.createContinuityClient(uri("localhost"), TOKEN, CONNECT_TIMEOUT, READ_TIMEOUT);

        assertThat(appliedSettings).extracting(HttpClientSettings::sslBundle)
                .containsExactly(trustedBundle, null);
        assertThat(appliedSettings).extracting(HttpClientSettings::redirects)
                .containsOnly(HttpRedirects.DONT_FOLLOW);
    }

    private RestClientBriefServiceClient serviceClient(URI baseUri, SslBundle sslBundle) {
        BriefRestClientFactory factory = new BriefRestClientFactory(new OutboundRestClients(
                RestClient.builder(),
                ClientHttpRequestFactoryBuilder.detect(),
                HttpClientSettings.defaults()
        ));
        return factory.createEditionServiceClient(baseUri, TOKEN, CONNECT_TIMEOUT, READ_TIMEOUT, sslBundle);
    }

    private URI uri(String host) {
        return URI.create("https://" + host + ":" + server.getAddress().getPort());
    }

    private static SSLContext serverSslContext(Path keyStorePath)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
