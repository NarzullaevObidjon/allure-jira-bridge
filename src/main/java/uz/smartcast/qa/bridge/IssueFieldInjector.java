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
 * Adds the fields Allure cannot send (array-typed Developer and Labels) to a Jira "create issue" body, whatever the
 * project. Developer is the issue's assignee, or the configured default when the issue has none. Values the request already has are never replaced. A project whose create screen lacks the Developer field
 * gets Jira's "not on the appropriate screen" error, which Allure shows in the defect form.
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

        List<String> added = new ArrayList<>();
        if (isEmpty(fields.get(props.developerFieldId()))) {
            String assignee = assigneeOf(fields);
            String developer = assignee != null ? assignee : props.developerUsername();
            ArrayNode developers = fields.putArray(props.developerFieldId());
            developers.addObject().put("name", developer);
            added.add(props.developerFieldId() + "=" + developer + (assignee != null ? " (assignee)" : " (default)"));
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

    /**
     * Login from {"assignee": {"name": "..."}}, or null when there is none to copy. "-1" is Jira's "Automatic", which
     * names no user.
     */
    private static String assigneeOf(ObjectNode fields) {
        String name = fields.path("assignee").path("name").asString("").trim();
        return name.isEmpty() || name.equals("-1") ? null : name;
    }

    /** For the log only: Allure may send the project as {"key": "QDH"} or {"id": "11000"}. */
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
