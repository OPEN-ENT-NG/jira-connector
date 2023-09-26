package fr.openent.jiraconnector.service;


import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface SsoService {

    /**
     * Generate an sso jira connection
     *
     * @return Future {@link Future <JsonArray>} containing newly created jira connection
     */
    Future<JsonArray> generate(JsonObject message);

}
