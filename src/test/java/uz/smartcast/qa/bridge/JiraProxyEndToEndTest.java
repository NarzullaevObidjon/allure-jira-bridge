package uz.smartcast.qa.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Allure-like client -> bridge -> fake Jira, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JiraProxyEndToEndTest {

    record Call(String method, String uri, String auth, String contentLength, String body) {
    }

    static final byte[] GZIPPED = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3};

    static final List<Call> calls = new CopyOnWriteArrayList<>();
    static final HttpServer fakeJira = startFakeJira();
    static final String JIRA = "http://localhost:" + fakeJira.getAddress().getPort();
    static final String SECRET = "test-secret-0123456789abcdef0123456789";

    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bridge.jira-url", () -> JIRA);
        r.add("bridge.path-secret", () -> SECRET);
        r.add("bridge.max-create-issue-body", () -> "1KB");
    }

    @AfterAll
    static void stop() {
        fakeJira.stop(0);
    }

    @BeforeEach
    void clear() {
        calls.clear();
    }

    @Test
    void createIssueGetsDeveloperAndLabelsAndKeepsAllureCredentials() throws Exception {
        HttpResponse<String> response = send("POST", "/rest/api/2/issue",
                "{\"fields\":{\"project\":{\"id\":\"11000\"},\"issuetype\":{\"id\":\"10002\"},\"summary\":\"Bug\"}}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"key\":\"QDH-200\"");
        Call call = calls.get(0);
        assertThat(call.auth()).isEqualTo("Bearer allure-integration-token");
        assertThat(call.body()).contains("\"customfield_10200\":[{\"name\":\"i.fedulaev\"}]", "\"labels\":[\"QA\"]",
                "\"summary\":\"Bug\"");
    }

    @Test
    void otherProjectsAreForwardedUnchanged() throws Exception {
        String body = "{\"fields\":{\"project\":{\"key\":\"SMAC\"},\"summary\":\"x\"}}";
        send("POST", "/rest/api/2/issue", body);

        assertThat(calls.get(0).body()).isEqualTo(body);
    }

    @Test
    void otherCallsPassThroughWithStatusHeadersAndQuery() throws Exception {
        HttpResponse<String> response = send("GET",
                "/rest/api/2/search?jql=project%20%3D%20QDH&fields=key", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("X-Fake-Jira")).contains("yes");
        assertThat(response.body()).contains("errorMessages");
        assertThat(calls.get(0).uri()).isEqualTo("/rest/api/2/search?jql=project%20%3D%20QDH&fields=key");
        assertThat(calls.get(0).auth()).isEqualTo("Bearer allure-integration-token");
    }

    @Test
    void issueLinksRedirectToRealJira() throws Exception {
        HttpResponse<String> response = send("GET", "/browse/QDH-118", null);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(JIRA + "/browse/QDH-118");
        assertThat(calls).isEmpty();
    }

    @Test
    void withoutTheRightSecretNothingReachesJira() throws Exception {
        String wrong = "x" + SECRET.substring(1);
        for (String path : new String[] {"/rest/api/2/myself", "/" + wrong + "/rest/api/2/myself",
                "/" + wrong + "/browse/QDH-1", "/" + wrong}) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(bridge(path))
                    .header("Authorization", "Bearer allure-integration-token").build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).doesNotContain(SECRET);
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void secretRootRedirectsToJiraRoot() throws Exception {
        HttpResponse<String> response = send("GET", "", null);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(JIRA + "/");
    }

    @Test
    void createIssueViaLatestApiWithTrailingSlashGetsFields() throws Exception {
        send("POST", "/rest/api/latest/issue/", "{\"fields\":{\"project\":{\"key\":\"QDH\"}}}");

        assertThat(calls.get(0).body()).contains("\"labels\":[\"QA\"]");
    }

    @Test
    void oversizedCreateIssueIsRefusedWithoutCallingJira() throws Exception {
        String summary = "x".repeat(2000);
        HttpResponse<String> response = send("POST", "/rest/api/2/issue",
                "{\"fields\":{\"project\":{\"key\":\"QDH\"},\"summary\":\"" + summary + "\"}}");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("errorMessages", "MAX_CREATE_ISSUE_BODY");
        assertThat(calls).isEmpty();
    }

    @Test
    void largeUploadsAreStreamedWithTheirLength() throws Exception {
        String body = "a".repeat(5 * 1024 * 1024);
        HttpResponse<String> response = send("POST", "/rest/api/2/issue/QDH-1/attachments", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(calls.get(0).contentLength()).isEqualTo(String.valueOf(body.length()));
        assertThat(calls.get(0).body()).isEqualTo(body);
    }

    @Test
    void compressedResponsesPassThroughUntouched() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(bridge("/" + SECRET + "/rest/api/2/gzip"))
                .header("Accept-Encoding", "gzip").build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(response.body()).isEqualTo(GZIPPED);
    }

    @Test
    void unparseableUrlGetsJiraStyle400() throws Exception {
        // java.net.http refuses to send a stray %, so write the request by hand.
        String response;
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET /" + SECRET + "/rest/api/2/search?jql=summary~50% HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(response).startsWith("HTTP/1.1 400").contains("errorMessages");
        assertThat(calls).isEmpty();
    }

    @Test
    void escapesCharactersThatJavaUriRejects() {
        assertThat(JiraProxyController.escapeIllegal("jql=labels=\"QA\"|x")).isEqualTo("jql=labels=%22QA%22%7Cx");
        assertThat(JiraProxyController.escapeIllegal("/rest/api/2/issue/QDH-1")).isEqualTo("/rest/api/2/issue/QDH-1");
    }

    private URI bridge(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(bridge("/" + SECRET + path))
                .header("Authorization", "Bearer allure-integration-token")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startFakeJira() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", JiraProxyEndToEndTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Length"),
                body));
        String path = ex.getRequestURI().getPath();
        int status;
        String response;
        if (path.matches("/rest/api/(2|latest)/issue/?")) {
            status = 201;
            response = "{\"id\":\"1\",\"key\":\"QDH-200\"}";
        } else if (path.endsWith("/attachments")) {
            status = 200;
            response = "[]";
        } else if (path.equals("/rest/api/2/gzip")) {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, GZIPPED.length);
            ex.getResponseBody().write(GZIPPED);
            ex.close();
            return;
        } else {
            status = 400;
            response = "{\"errorMessages\":[\"fake\"],\"errors\":{}}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("X-Fake-Jira", "yes");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
