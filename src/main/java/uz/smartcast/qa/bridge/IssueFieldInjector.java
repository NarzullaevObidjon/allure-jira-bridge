package uz.smartcast.qa.bridge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
        } catch (JacksonException e) {
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
        return new Result(json.writeValueAsBytes(root), project, added);
    }

    /** Allure may send the project as {"key": "QDH"} or {"id": "11000"}; both are accepted in the config. */
    private boolean inScope(ObjectNode fields) {
        JsonNode project = fields.path("project");
        return props.projects().contains(project.path("key").asString(""))
                || props.projects().contains(project.path("id").asString(""));
    }

    private static String projectOf(ObjectNode fields) {
        JsonNode project = fields.get("project");
        if (project == null || !project.isObject()) {
            return null;
        }
        return project.hasNonNull("key") ? project.get("key").asString() : project.path("id").asString(null);
    }

    private static boolean isEmpty(JsonNode node) {
        return node == null || node.isNull() || (node.isArray() && node.isEmpty())
                || (node.isString() && node.asString().isBlank());
    }
}
