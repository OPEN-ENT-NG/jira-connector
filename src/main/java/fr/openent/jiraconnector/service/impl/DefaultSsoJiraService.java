package fr.openent.jiraconnector.service.impl;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.core.constants.Field;
import fr.openent.jiraconnector.service.ServiceFactory;
import fr.openent.jiraconnector.service.SsoService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.user.UserUtils;

public class DefaultSsoJiraService implements SsoService {

    protected static final Logger log = LoggerFactory.getLogger(DefaultSsoJiraService.class);
    private final EventBus eb;
    private final JiraConnectorConfig config;

    public DefaultSsoJiraService(ServiceFactory serviceFactory) {
        this.eb = serviceFactory.eventBus();
        this.config = serviceFactory.jiraConnectorConfig();
    }

    @Override
    public Future<JsonArray> generate(JsonObject request) {
        Promise<JsonArray> promise = Promise.promise();
        String userId = request.getString(Field.USER_ID);
        String host = request.getString(Field.HOST);
        String serviceProviderEntityId = request.getString(Field.SERVICE_PROVIDER_ID);
        String query = "MATCH (u:User {id: {userId}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
                "WITH u, COLLECT(DISTINCT s.UAI + ' - ' + s.name) AS structures, COLLECT(DISTINCT 'Académie de ' + s.academy) AS academies " +
                "OPTIONAL MATCH (u)-[rf:HAS_FUNCTION]->()-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
                "WITH u, structures, academies, COLLECT(DISTINCT [f.externalId, rf.scope]) AS admlStructures " +
                "RETURN u.login as login, u.displayName as displayName, COALESCE(u.emailInternal, u.email) as email, structures, academies, admlStructures;";

        Neo4j.getInstance().execute(query, new JsonObject().put(Field.USER_ID, userId), Neo4jResult.validUniqueResultHandler(evt -> {
            if (evt.isLeft()) {
                log.error(String.format("[Jira-Connector@%s::generate] Fail to get users by ids %s",
                        this.getClass().getSimpleName(), evt.left().getValue()));
                promise.fail(evt.left().getValue());
                return;
            }

            JsonArray result = new JsonArray();
            JsonObject user = evt.right().getValue();
            result.add(new JsonObject().put(Field.LOGIN, user.getString(Field.LOGIN, "")));
            result.add(new JsonObject().put(Field.DISPLAY_NAME, user.getString(Field.DISPLAY_NAME, "")));
            result.add(new JsonObject().put(Field.EMAIL, user.getString(Field.EMAIL, "")));

            // Ajoutez des groupes pour Jira
            result.add(new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getUsers()));
            addingGroups(user, Field.STRUCTURES, result);
            addingGroups(user, Field.ACADEMIES, result);

            UserUtils.getUserInfos(eb, userId, userInfo -> {
                String userType = userInfo.getType();
                if (userType != null && (userInfo.isADML())) {
                    if (isAdminRegion(user)) {
                        result.add(new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getRegion()));
                        promise.complete(result);
                    } else {
                        result.add(new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getAdmin()));
                        this.getAdminStructures(user, result)
                                .onFailure(promise::fail)
                                .onSuccess(adminStructures -> {
                                    promise.complete(result);
                                });
                    }
                } else if (userType != null && (userType.equals(Field.TEACHER) || userType.equals(Field.PERSONNEL))) {
                    result.add(new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getPersonnel()));
                    promise.complete(result);
                } else {
                    promise.complete(result);
                }
            });
        }));
        return promise.future();
    }



    private static void addingGroups(JsonObject user, String key, JsonArray result) {
        user.getJsonArray(key, new JsonArray()).stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .forEach(value -> result.add(new JsonObject().put(Field.GROUP, value)));
    }

    private boolean isAdminRegion(JsonObject user) {
        JsonArray structures = user.getJsonArray(Field.STRUCTURES);
        String adminUai = this.config.getJiraUaiAdmin();

        if (structures != null && adminUai != null) {
            return structures.stream().anyMatch(structure -> {
                String structureUai = structure.toString().split(" - ")[0];
                return adminUai.equals(structureUai);
            });
        }
        return false;
    }

    private static JsonArray getAdminLocalStructures(JsonObject user) {
        JsonArray result = new JsonArray();
        if (user.containsKey(Field.ADML_STRUCTURES) && user.getJsonArray(Field.ADML_STRUCTURES).size() > 0) {
            user.getJsonArray(Field.ADML_STRUCTURES).forEach(structureResult -> {
                JsonArray structure = (JsonArray) structureResult;
                if (structure.size() > 0 && structure.getString(0).equals(Field.ADML_LOCAL)) {
                    result.addAll(structure.getJsonArray(1));
                }
            });
        }
        return result;
    }

    public Future<JsonArray> getAdminStructures(JsonObject user, JsonArray result) {
        Promise<JsonArray> promise = Promise.promise();

        String queryAdml = "MATCH (s:Structure) " +
                "WHERE s.id IN {idsStructure} " +
                "RETURN collect(DISTINCT s.UAI + ' - ' + s.name) AS structures, collect(DISTINCT 'Académie de ' + s.academy) AS academies;";

        JsonObject params = new JsonObject().put(Field.IDS_STRUCTURE, getAdminLocalStructures(user));

        Neo4j.getInstance().execute(queryAdml, params, Neo4jResult.validUniqueResultHandler(admlStructuresResult -> {
            if (admlStructuresResult.isLeft()) {
                log.error(String.format("[Jira-Connector@%s::generate] Fail to get adml structures by ids %s",
                        this.getClass().getSimpleName(), admlStructuresResult.left().getValue()));
                promise.fail(admlStructuresResult.left().getValue());
            } else {
                JsonObject adminStructuresResult = admlStructuresResult.right().getValue();

                // Ajoutez les structures et académies à adminStructures
                addingGroups(adminStructuresResult, Field.STRUCTURES, result);
                addingGroups(adminStructuresResult, Field.ACADEMIES, result);
                promise.complete(result);
            }
        }));

        return promise.future();
    }

}
