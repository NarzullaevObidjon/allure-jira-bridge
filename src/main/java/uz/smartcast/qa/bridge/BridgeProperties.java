package uz.smartcast.qa.bridge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bridge")
public record BridgeProperties(
        /** Real Jira that requests are forwarded to. */
        @NotNull URI jiraUrl,
        /**
         * First path segment every request must start with (https://bridge/&lt;secret&gt;/rest/...); without it the
         * bridge answers 404, so it is not an open relay to Jira. Set it in Allure as part of the integration URL.
         */
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{32,}", message = "must be at least 32 characters of [A-Za-z0-9_-]"
                + " (e.g. openssl rand -hex 32)") String pathSecret,
        @NotBlank String developerFieldId,
        /** Jira login put into the Developer field when the request has none. */
        @NotBlank String developerUsername,
        /** Labels put on the issue when the request has none. */
        @NotEmpty Set<String> labels,
        /**
         * Projects (key or numeric id) whose create screen has the Developer field. Other projects are forwarded
         * untouched: Jira rejects a field that is not on the project's screen.
         */
        @NotEmpty Set<String> projects,
        /** "Create issue" bodies are read into memory to add fields; larger ones are refused with 413. */
        @DefaultValue("1MB") DataSize maxCreateIssueBody) {
}
