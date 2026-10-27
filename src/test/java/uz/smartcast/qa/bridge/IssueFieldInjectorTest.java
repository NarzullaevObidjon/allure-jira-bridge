package uz.smartcast.qa.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IssueFieldInjectorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final IssueFieldInjector injector = new IssueFieldInjector(json, new BridgeProperties(
            URI.create("https://jira"), "s".repeat(32), "customfield_10200", "i.fedulaev", Set.of("QA"), Set.of("QDH", "11000"),
            DataSize.ofMegabytes(1)));

    private JsonNode fields(String body) throws Exception {
        var result = injector.inject(body.getBytes(StandardCharsets.UTF_8));
        return json.readTree(result.body()).get("fields");
    }

    @Test
    void addsDeveloperAndLabelsWhenAllureSendsNone() throws Exception {
        JsonNode f = fields("{\"fields\":{\"project\":{\"key\":\"QDH\"},\"issuetype\":{\"name\":\"Task\"},\"summary\":\"S\"}}");

        assertThat(f.get("customfield_10200").toString()).isEqualTo("[{\"name\":\"i.fedulaev\"}]");
        assertThat(f.get("labels").toString()).isEqualTo("[\"QA\"]");
        assertThat(f.get("summary").asString()).isEqualTo("S");
    }

    @Test
    void matchesProjectById() throws Exception {
        assertThat(fields("{\"fields\":{\"project\":{\"id\":\"11000\"}}}").has("labels")).isTrue();
    }

    @Test
    void replacesEmptyValues() throws Exception {
        JsonNode f = fields("{\"fields\":{\"project\":{\"key\":\"QDH\"},\"labels\":[],\"customfield_10200\":null}}");

        assertThat(f.get("labels").toString()).isEqualTo("[\"QA\"]");
        assertThat(f.get("customfield_10200").toString()).isEqualTo("[{\"name\":\"i.fedulaev\"}]");
    }

    @Test
    void keepsValuesTheRequestAlreadyHas() throws Exception {
        String body = "{\"fields\":{\"project\":{\"key\":\"QDH\"},\"labels\":[\"UI\"],"
                + "\"customfield_10200\":[{\"name\":\"other.dev\"}]}}";
        var result = injector.inject(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result.added()).isEmpty();
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void leavesOtherProjectsAndNonJsonUntouched() {
        for (String body : new String[] {"{\"fields\":{\"project\":{\"key\":\"SMAC\"}}}", "not json", "{}", "[]"}) {
            var result = injector.inject(body.getBytes(StandardCharsets.UTF_8));
            assertThat(result.added()).isEmpty();
            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
    }
}
