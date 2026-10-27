package uz.smartcast.qa.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adds the fields Allure cannot send (array-typed Developer and Labels) to a Jira "create issue" body. Values the
 * request already has are never replaced.
 */
@Component
public class IssueFieldInjector {

    /** What was added, for the log; empty when the body was forwarded unchanged. */
    public record Result(byte[] body, String project, List<String> added) {
    }

    private final ObjectMapper json;
    private final BridgeProperties props;

    public IssueFieldInjector(ObjectMapper json, BridgeProperties props) {
        this.json = json;
        this.props = props;
    }

    public Result inject(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            return new Result(body, null, List.of()); // not JSON: let Jira answer it
        }
        if (root == null || !(root.get("fields") instanceof ObjectNode fields)) {
            return new Result(body, null, List.of());
        }
        String project = projectOf(fields);
        if (project == null || !inScope(fields)) {
            return new Result(body, project, List.of());
        }

        List<String> added = new ArrayList<>();
        if (isEmpty(fields.get(props.developerFieldId()))) {
            ArrayNode developers = fields.putArray(props.developerFieldId());
            developers.addObject().put("name", props.developerUsername());
            added.add(props.developerFieldId() + "=" + props.developerUsername());
        }
        if (isEmpty(fields.get("labels"))) {
            ArrayNode labels = fields.putArray("labels");
            props.labels().forEach(labels::add);
            added.add("labels=" + props.labels());
        }
        if (added.isEmpty()) {
            return new Result(body, project, added);
        }
        try {
            return new Result(json.writeValueAsBytes(root), project, added);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Allure may send the project as {"key": "QDH"} or {"id": "11000"}; both are accepted in the config. */
    private boolean inScope(ObjectNode fields) {
        JsonNode project = fields.path("project");
        return props.projects().contains(project.path("key").asText(""))
                || props.projects().contains(project.path("id").asText(""));
    }

    private static String projectOf(ObjectNode fields) {
        JsonNode project = fields.get("project");
        if (project == null || !project.isObject()) {
            return null;
        }
        return project.hasNonNull("key") ? project.get("key").asText() : project.path("id").asText(null);
    }

    private static boolean isEmpty(JsonNode node) {
        return node == null || node.isNull() || (node.isArray() && node.isEmpty())
                || (node.isTextual() && node.asText().isBlank());
    }
}
