package uz.smartcast.qa.bridge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stands in for Jira in the Allure integration. REST calls go to the real Jira as they are (Allure's own credentials
 * included); only "create issue" gets the missing Developer and Labels. Browser links (/browse/QDH-1) are redirected
 * to the real Jira, so people never log in through this service.
 * <p>
 * Only "create issue" is held in memory (it has to be rewritten, and its size is capped); every other request and
 * response, attachments included, is streamed through.
 * <p>
 * Every path starts with the configured secret (/&lt;secret&gt;/rest/...). It is stripped before forwarding and never
 * logged; a wrong or missing secret gets a bare 404.
 */
@RestController
public class JiraProxyController {

    private static final Logger log = LoggerFactory.getLogger(JiraProxyController.class);
    private static final Pattern CREATE_ISSUE = Pattern.compile("/rest/api/(2|latest)/issue/?");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Per-connection headers, never forwarded in either direction. */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    /** Hop-by-hop plus the ones the JDK client sets itself. */
    private static final Set<String> REQUEST_NOT_FORWARDED = Set.of("connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "expect");

    private final HttpClient http;
    private final IssueFieldInjector injector;
    private final ObjectMapper json;
    private final String jiraBase;
    private final byte[] pathSecret;
    private final int maxCreateIssueBody;

    public JiraProxyController(HttpClient http, IssueFieldInjector injector, ObjectMapper json,
            BridgeProperties props) {
        this.http = http;
        this.injector = injector;
        this.json = json;
        this.jiraBase = props.jiraUrl().toString().replaceAll("/+$", "");
        this.pathSecret = props.pathSecret().getBytes(StandardCharsets.UTF_8);
        this.maxCreateIssueBody = Math.toIntExact(props.maxCreateIssueBody().toBytes());
    }

    @RequestMapping("/{secret}/rest/**")
    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = jiraPath(request);
        if (path == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
        } else if ("POST".equals(request.getMethod()) && CREATE_ISSUE.matcher(path).matches()) {
            createIssue(request, response, path);
        } else {
            stream(request, response, path);
        }
    }

    /** Issue links that Allure builds from the integration URL open the real Jira. */
    @GetMapping({"/{secret}/browse/**", "/{secret}/secure/**", "/{secret}/projects/**", "/{secret}", "/{secret}/"})
    public ResponseEntity<Void> redirectToJira(HttpServletRequest request) {
        String path = jiraPath(request);
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(target(request, path)).build();
    }

    /** A URL java.net.URI cannot parse (e.g. a stray % in JQL) or a header value the JDK client refuses. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(IllegalArgumentException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("{} -> 400 {}", request.getMethod(), e.getMessage());
        writeError(response, HttpStatus.BAD_REQUEST, "allure-jira-bridge cannot forward this request: "
                + e.getMessage());
    }

    /** The raw request path without the leading /&lt;secret&gt;, or null when the secret is missing or wrong. */
    private String jiraPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int end = uri.indexOf('/', 1);
        String given = end < 0 ? uri.substring(1) : uri.substring(1, end);
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pathSecret)) {
            return null;
        }
        return end < 0 ? "/" : uri.substring(end);
    }

    private void createIssue(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        byte[] body = request.getContentLengthLong() > maxCreateIssueBody
                ? null
                : request.getInputStream().readNBytes(maxCreateIssueBody + 1);
        if (body == null || body.length > maxCreateIssueBody) {
            log.warn("Create issue refused: body is over {} bytes", maxCreateIssueBody);
            writeError(response, HttpStatus.CONTENT_TOO_LARGE, "Create issue request is over " + maxCreateIssueBody
                    + " bytes (allure-jira-bridge limit, see MAX_CREATE_ISSUE_BODY)");
            return;
        }

        IssueFieldInjector.Result injected = injector.inject(body);
        HttpResponse<byte[]> upstream = send(request, response, path, injected.body().length == 0
                ? BodyPublishers.noBody()
                : BodyPublishers.ofByteArray(injected.body()), BodyHandlers.ofByteArray());
        if (upstream == null) {
            return;
        }
        logCreate(injected, upstream);
        copyStatusAndHeaders(upstream, response);
        response.getOutputStream().write(upstream.body());
    }

    private void stream(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        HttpResponse<InputStream> upstream = send(request, response, path, streamedBody(request),
                BodyHandlers.ofInputStream());
        if (upstream == null) {
            return;
        }
        log.debug("{} {} -> {}", request.getMethod(), path, upstream.statusCode());
        copyStatusAndHeaders(upstream, response);
        try (InputStream in = upstream.body()) {
            in.transferTo(response.getOutputStream());
        }
    }

    /** The client's body as it arrives, keeping its Content-Length when it has one. */
    private static BodyPublisher streamedBody(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length == 0 || (length < 0 && request.getHeader("Transfer-Encoding") == null)) {
            return BodyPublishers.noBody();
        }
        BodyPublisher in = BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return length > 0 ? BodyPublishers.fromPublisher(in, length) : in;
    }

    /** Sends the request to Jira; on failure writes a Jira-style error to the client and returns null. */
    private <T> HttpResponse<T> send(HttpServletRequest request, HttpServletResponse response, String path,
            BodyPublisher body, BodyHandler<T> handler) throws IOException {
        HttpRequest.Builder upstream = HttpRequest.newBuilder(target(request, path))
                .timeout(TIMEOUT)
                .method(request.getMethod(), body);
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!REQUEST_NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : Collections.list(request.getHeaders(name))) {
                    upstream.header(name, value);
                }
            }
        }

        String call = request.getMethod() + " " + path;
        try {
            return http.send(upstream.build(), handler);
        } catch (HttpTimeoutException e) {
            log.error("{} -> Jira did not answer in {} s", call, TIMEOUT.toSeconds());
            writeError(response, HttpStatus.GATEWAY_TIMEOUT,
                    "Jira did not answer allure-jira-bridge in " + TIMEOUT.toSeconds() + " s");
        } catch (IOException e) {
            log.error("{} -> Jira unreachable: {}", call, e.toString());
            writeError(response, HttpStatus.BAD_GATEWAY, "Jira is unreachable from allure-jira-bridge: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "allure-jira-bridge is shutting down");
        }
        return null;
    }

    private static void copyStatusAndHeaders(HttpResponse<?> upstream, HttpServletResponse response) {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":") && !HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
    }

    private URI target(HttpServletRequest request, String path) {
        String query = request.getQueryString();
        return URI.create(jiraBase + escapeIllegal(path)
                + (query == null ? "" : "?" + escapeIllegal(query)));
    }

    /** Tomcat lets through characters like | or " (JQL) that java.net.URI rejects; percent-encode only those. */
    static String escapeIllegal(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c <= 0x20 || c >= 0x7f || "\"<>\\^`{|}[]".indexOf(c) >= 0) {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xff));
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void logCreate(IssueFieldInjector.Result injected, HttpResponse<byte[]> response) {
        String added = injected.added().isEmpty() ? "nothing added" : "added " + injected.added();
        boolean readable = response.headers().firstValue("Content-Encoding").isEmpty();
        String answer = readable ? truncate(new String(response.body(), StandardCharsets.UTF_8)) : "<compressed>";
        if (response.statusCode() >= 400) {
            log.warn("Create issue in {} ({}) -> {} {}", injected.project(), added, response.statusCode(), answer);
        } else {
            log.info("Create issue in {} ({}) -> {} {}", injected.project(), added, response.statusCode(), answer);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000) + "…";
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.putArray("errorMessages").add(message);
        body.putObject("errors");
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}
