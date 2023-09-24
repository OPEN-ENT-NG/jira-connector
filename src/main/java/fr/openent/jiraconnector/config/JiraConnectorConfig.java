package fr.openent.jiraconnector.config;

import fr.openent.jiraconnector.core.constants.Field;
import io.vertx.core.json.JsonObject;

public class JiraConnectorConfig {

    private final String address;
    private final JiraSamlResponse jiraSamlResponse;

    public JiraConnectorConfig(JsonObject config) {
        this.address = config.getString(Field.ADDRESS, "fr.openent.jiraconnector");
        this.jiraSamlResponse = new JiraSamlResponse(config.getJsonObject(Field.JIRA_SAML_RESPONSE, new JsonObject()));
    }

    public String address() {
        return this.address;
    }

    public JiraSamlResponse jiraSamlResponse() {
        return this.jiraSamlResponse;
    }

    public static class JiraSamlResponse {

        public JiraSamlResponse(JsonObject websocketConfig) {
        }
    }
}
