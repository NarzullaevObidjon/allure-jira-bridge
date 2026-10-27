package uz.smartcast.qa.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Bridge pointed at a port nobody listens on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JiraUnreachableTest {

    static final String SECRET = "test-secret-0123456789abcdef0123456789";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        r.add("bridge.jira-url", () -> "http://localhost:" + closedPort);
        r.add("bridge.path-secret", () -> SECRET);
    }

    @Test
    void answersJiraStyle502() throws Exception {
        URI myself = URI.create("http://localhost:" + port + "/" + SECRET + "/rest/api/2/myself");
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(myself).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).contains("\"errorMessages\":[\"Jira is unreachable");
    }
}
