package fr.openent.jiraconnector.service.impl;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.core.constants.Field;
import fr.openent.jiraconnector.service.ServiceFactory;
import fr.openent.jiraconnector.service.SsoService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.user.UserUtils;

import java.util.ArrayList;
import java.util.List;

public class DefaultSsoJiraService implements SsoService {

    protected static final Logger log = LoggerFactory.getLogger(DefaultSsoJiraService.class);
    private final EventBus eb;
    private final JiraConnectorConfig config;
    private final HttpClient httpClient;

    private final String scimUrl;
    private final String scimAuthorization;
    private final String adminJiraApiUrl;
    private final String adminJiraApiAuthorization;

    public DefaultSsoJiraService(ServiceFactory serviceFactory) {
        this.eb = serviceFactory.eventBus();
        this.config = serviceFactory.jiraConnectorConfig();
        this.httpClient = serviceFactory.vertx().createHttpClient(new HttpClientOptions());

        this.scimUrl = this.config.getScimUrl();

        if (!this.config.getScimToken().isEmpty()) {
            this.scimAuthorization = "Bearer " + this.config.getScimToken();
        } else {
            this.scimAuthorization = "";
        }
        this.adminJiraApiAuthorization = "Bearer " + this.config.getAdminJiraApiAuthorization();
        this.adminJiraApiUrl = this.config.getAdminJiraApiUrl();
    }

    @Override
    public Future<JsonArray> generate(JsonObject request) {
        Promise<JsonArray> promise = Promise.promise();
        String userId = request.getString(Field.USER_ID);
        String query = "MATCH (u:User {id: {userId}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
                "WITH u, COLLECT(DISTINCT s.UAI + ' - ' + s.name) AS structures, COLLECT(DISTINCT 'Académie de ' + s.academy) AS academies "
                +
                "OPTIONAL MATCH (u)-[rf:HAS_FUNCTION]->()-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
                "WITH u, structures, academies, COLLECT(DISTINCT [f.externalId, rf.scope]) AS admlStructures " +
                "RETURN u.login as login, u.displayName as displayName, COALESCE(u.emailInternal, u.email) as email, structures, academies, admlStructures;";

        Neo4j.getInstance().execute(query, new JsonObject().put(Field.USER_ID, userId),
                Neo4jResult.validUniqueResultHandler(evt -> {
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

                    this.createUserScimJira(user).onFailure(err -> {
                        log.error(
                                "[Jira-Connector@%s::generate] Error creating user in Jira SCIM: " + err.getMessage());
                        promise.fail(err);
                        return;
                    }).onSuccess(userJiraId -> {
                        UserUtils.getUserInfos(eb, userId, userInfo -> {
                            String userType = userInfo.getType();
                            if (userType != null && (userInfo.isADML())) {
                                if (isAdminRegion(user)) {
                                    result.add(
                                            new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getRegion()));
                                    promise.complete(result);
                                } else {
                                    result.add(
                                            new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getAdmin()));
                                    this.getAdminStructures(user, result)
                                            .onFailure(promise::fail)
                                            .onSuccess(adminStructures -> {
                                                promise.complete(result);
                                            });
                                }
                            } else if (userType != null
                                    && (userType.equals(Field.TEACHER) || userType.equals(Field.PERSONNEL))) {
                                result.add(
                                        new JsonObject().put(Field.GROUP, this.config.jiraSsoGroups().getPersonnel()));
                                promise.complete(result);
                            } else {
                                promise.complete(result);
                            }

                            // SYNC GROUPS USER SCIM JIRA
                            if (userJiraId != null) {
                                this.syncGroupsUserAdminJira(userJiraId, result);
                            }
                        });
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

    private Future<String> checkUserExistsScimJira(JsonObject user) {
        Promise<String> promise = Promise.promise();
        String url = this.scimUrl + "/Users?filter=userName%20eq%20\"" + user.getString(Field.EMAIL) + "\"";
        String accept = "application/json";

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.GET)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.scimAuthorization)
                        .add("Accept", accept)))
                .flatMap(HttpClientRequest::send)
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        response.bodyHandler(body -> {
                            JsonObject responseBody = new JsonObject(body.toString());
                            if (responseBody.containsKey("totalResults")
                                    && responseBody.getInteger("totalResults") > 0) {
                                // GET USER ID
                                JsonArray resources = responseBody.getJsonArray("Resources");
                                if (resources != null && !resources.isEmpty()
                                        && resources.getJsonObject(0)
                                                .containsKey("urn:scim:schemas:extension:atlassian-external:1.0")) {
                                    String userJiraId = resources.getJsonObject(0)
                                            .getJsonObject("urn:scim:schemas:extension:atlassian-external:1.0")
                                            .getString("atlassianAccountId");
                                    promise.complete(userJiraId);
                                } else {
                                    promise.complete(null);
                                }
                            } else {
                                promise.fail("User does not exist in Jira SCIM");
                            }
                        });
                    } else {
                        log.error("[Jira-Connector@%s::checkUserExistsScimJira] User does not exist in Jira SCIM: "
                                + response.statusCode());
                        promise.fail("Failed to check user exists in Jira SCIM");
                    }
                }).onFailure(err -> {
                    log.error("[Jira-Connector@%s::checkUserExistsScimJira] Failed to check user exists in Jira SCIM: "
                            + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }

    private Future<String> createUserScimJira(JsonObject user) {

        if (this.scimUrl.isEmpty() || this.scimAuthorization.isEmpty()) {
            log.info(
                    "[Jira-Connector@%s::createUserScimJira] SCIM URL or SCIM Token is empty, skipping user creation in Jira SCIM");
            return Future.succeededFuture();
        }

        Promise<String> promise = Promise.promise();
        this.checkUserExistsScimJira(user).onFailure(err -> {
            log.info(
                    "[Jira-Connector@%s::createUserScimJira] User does not exist in Jira SCIM Creating user in Jira SCIM");
            String url = this.scimUrl + "/Users";
            String contentType = "application/scim+json";
            String accept = "application/scim+json";
            String body = new JsonObject()
                    .put("schemas", new JsonArray().add("urn:ietf:params:scim:schemas:core:2.0:User"))
                    .put("userName", user.getString(Field.EMAIL))
                    .put("name", new JsonObject()
                            .put("givenName", user.getString(Field.FIRST_NAME))
                            .put("familyName", user.getString(Field.LAST_NAME)))
                    .put("displayName", user.getString(Field.DISPLAY_NAME))
                    .put("emails", new JsonArray().add(new JsonObject()
                            .put("value", user.getString(Field.EMAIL))
                            .put("primary", true)))
                    .put("active", true)
                    .encode();

            httpClient
                    .request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.POST)
                            .setHeaders(new HeadersMultiMap().add("Authorization", this.scimAuthorization)
                                    .add("Content-Type", contentType).add("Accept", accept)))
                    .flatMap(request -> request.send(body)).onSuccess(response -> {
                        log.info(
                                "[Jira-Connector@%s::createUserScimJira] User creation in Jira SCIM succeeded for user: "
                                        + user.getString(Field.LOGIN));
                        // GET USER ID FROM RESPONSE BODY
                        JsonObject responseBody = new JsonObject(response.body().toString());
                        if (responseBody.containsKey("id")) {
                            String userJiraId = responseBody.getString("id");
                            promise.complete(userJiraId);
                        } else {
                            promise.complete(null);
                        }
                    }).onFailure(errNew -> {
                        log.error("[Jira-Connector@%s::createUserScimJira] Failed to create user in Jira SCIM: "
                                + errNew.getMessage());
                        promise.fail(errNew);
                    });
        }).onSuccess(userJiraId -> {

            promise.complete(userJiraId);
            return;
        });
        return promise.future();
    }

    private Future<Void> syncGroupsUserAdminJira(String userJiraId, JsonArray result) {

        // GET GROUPS FROM RESULT
        final JsonArray groups = new JsonArray();

        for (Object item : result) {
            JsonObject jsonObject = (JsonObject) item;
            if (jsonObject.containsKey(Field.GROUP)) {
                groups.add(jsonObject.getString(Field.GROUP).toLowerCase());
            }
        }

        if (groups.isEmpty()) {
            log.error("[Jira-Connector@%s::syncGroupsUserAdminJira] No groups found for user: " + userJiraId);
            return Future.succeededFuture();
        }

        Promise<Void> promise = Promise.promise();
        // SYNC GROUPS USER WITH Admin JIRA API

        this.getUserGroupsJira(userJiraId).onFailure(err -> {
            log.error("[Jira-Connector@%s::syncGroupsUserAdminJira] Failed to get user groups from Jira API: "
                    + err.getMessage());
            promise.fail(err);
        }).onSuccess(groupsJira -> {
            /**
             * Compare groupsJira with groups base on the name
             * if the group is in groupJira, remove the object from groups and groupsJira
             * if at the end of the loop, groups is not empty, create the groups in jira and
             * add the user to the group, if the group already exist in jira, add the user
             * to the group
             * if at the end of the loop, groupsJira is not empty, remove the users from the
             * groups in jira
             */
            for (Object groupJira : groupsJira.copy()) {
                JsonObject groupJiraObject = (JsonObject) groupJira;
                if (groups.contains(groupJiraObject.getString("name").toLowerCase())) {
                    groups.remove(groupJiraObject.getString("name").toLowerCase());
                    groupsJira.remove(groupJiraObject);
                }
            }

            if (groups.isEmpty() && groupsJira.isEmpty()) {
                promise.complete();
            } else {
                // Collect all futures for async operations
                List<Future<Void>> allFutures = new ArrayList<>();

                // Add futures for groups to add
                for (Object groupObject : groups) {
                    String groupName = (String) groupObject;

                    Future<Void> addGroupFuture = this.getGroupIdJira(groupName)
                            .flatMap(groupId -> {
                                if (groupId != null) {
                                    return this.addUserToGroupJira(groupId, userJiraId);
                                } else {
                                    return this.createGroupJira(groupName)
                                            .flatMap(groupIdCreated -> this.addUserToGroupJira(groupIdCreated,
                                                    userJiraId));
                                }
                            })
                            .onFailure(err -> {
                                log.error("[Jira-Connector@%s::syncGroupsUserAdminJira] Failed to process group "
                                        + groupName
                                        + ": " + err.getMessage());
                            });

                    allFutures.add(addGroupFuture);
                }

                // Add futures for groups to remove
                for (Object groupJiraObject : groupsJira) {
                    JsonObject groupJira = (JsonObject) groupJiraObject;
                    Future<Void> removeGroupFuture = this.removeUserFromGroupJira(groupJira.getString("id"), userJiraId)
                            .onFailure(err -> {
                                log.error(
                                        "[Jira-Connector@%s::syncGroupsUserAdminJira] Failed to remove user from group "
                                                + groupJira.getString("id") + ": " + err.getMessage());
                            });

                    allFutures.add(removeGroupFuture);
                }

                // Wait for all operations to complete
                if (allFutures.isEmpty()) {
                    promise.complete();
                } else {
                    Future.all(allFutures)
                            .onFailure(err -> {
                                log.error("[Jira-Connector@%s::syncGroupsUserAdminJira] Some operations failed: "
                                        + err.getMessage());
                                promise.fail(err);
                            })
                            .onSuccess(v -> {
                                promise.complete();
                            });
                }
            }
        });

        return promise.future();
    }

    private Future<JsonArray> getUserGroupsJira(String userJiraId) {
        Promise<JsonArray> promise = Promise.promise();

        String url = this.adminJiraApiUrl + "/groups?limit=100&accountIds=" + userJiraId;
        String accept = "application/json";

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.GET)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.adminJiraApiAuthorization)
                        .add("Accept", accept)))
                .flatMap(HttpClientRequest::send)
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        response.bodyHandler(body -> {
                            JsonObject responseBody = new JsonObject(body.toString());
                            if (responseBody.containsKey("data") && responseBody.getJsonArray("data").size() > 0) {
                                JsonArray data = responseBody.getJsonArray("data");
                                promise.complete(data);
                            } else {
                                promise.complete(new JsonArray());
                            }
                        });
                    } else {
                        log.error("[Jira-Connector@%s::getUserGroupsJira] Failed to get user groups from Jira API: "
                                + response.statusCode());
                        promise.fail("Failed to get user groups from Jira API");
                    }
                }).onFailure(err -> {
                    log.error("[Jira-Connector@%s::getUserGroupsJira] Failed to get user groups from Jira API: "
                            + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }

    private Future<String> getGroupIdJira(String groupName) {
        Promise<String> promise = Promise.promise();
        String url = this.adminJiraApiUrl + "/groups?limit=100&searchTerm=" + groupName;
        String accept = "application/json";

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.GET)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.adminJiraApiAuthorization)
                        .add("Accept", accept)))
                .flatMap(HttpClientRequest::send)
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        response.bodyHandler(body -> {
                            JsonObject responseBody = new JsonObject(body.toString());
                            if (responseBody.containsKey("data") && responseBody.getJsonArray("data").size() > 0) {
                                JsonArray data = responseBody.getJsonArray("data");
                                if (data.getJsonObject(0).containsKey("id")) {
                                    String groupId = data.getJsonObject(0).getString("id");
                                    promise.complete(groupId);
                                } else {
                                    promise.complete(null);
                                }
                            } else {
                                promise.complete(null);
                            }
                        });
                    } else {
                        log.error("[Jira-Connector@%s::getGroupIdJira] Failed to get group from Jira API: "
                                + response.statusCode());
                        promise.fail("Failed to get group from Jira API");
                    }
                }).onFailure(err -> {
                    log.error("[Jira-Connector@%s::getGroupIdJira] Failed to get group from Jira API: "
                            + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }

    private Future<String> createGroupJira(String groupName) {
        Promise<String> promise = Promise.promise();
        String url = this.adminJiraApiUrl + "/groups";
        String accept = "application/json";
        String body = new JsonObject().put("name", groupName).encode();

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.POST)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.adminJiraApiAuthorization)
                        .add("Accept", accept).add("Content-Type", "application/json")))
                .flatMap(request -> request.send(body))
                .onSuccess(response -> {
                    if (response.statusCode() == 201) {
                        response.bodyHandler(responseBody -> {
                            JsonObject responseBodyJson = new JsonObject(responseBody.toString());
                            if (responseBodyJson.containsKey("id")) {
                                String groupId = responseBodyJson.getString("id");
                                promise.complete(groupId);
                            } else {
                                promise.complete(null);
                            }
                        });
                    } else {
                        log.error("[Jira-Connector@%s::createGroupJira] Failed to create group from Jira API: "
                                + response.statusCode());
                        promise.fail("Failed to create group from Jira API");
                    }
                }).onFailure(err -> {
                    log.error("[Jira-Connector@%s::createGroupJira] Failed to create group from Jira API: "
                            + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }

    private Future<Void> addUserToGroupJira(String groupJiraId, String userJiraId) {
        Promise<Void> promise = Promise.promise();
        String url = this.adminJiraApiUrl + "/groups/" + groupJiraId + "/memberships";
        String accept = "application/json";
        String body = new JsonObject().put("accountId", userJiraId).encode();

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.POST)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.adminJiraApiAuthorization)
                        .add("Accept", accept).add("Content-Type", "application/json")))
                .flatMap(request -> request.send(body))
                .onSuccess(response -> {
                    promise.complete();
                }).onFailure(err -> {
                    log.error("[Jira-Connector@%s::addUserToGroupJira] Failed to add user to group from Jira API: "
                            + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }

    private Future<Void> removeUserFromGroupJira(String groupJiraId, String userJiraId) {
        Promise<Void> promise = Promise.promise();
        String url = this.adminJiraApiUrl + "/groups/" + groupJiraId + "/memberships/" + userJiraId;
        String accept = "application/json";

        httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.DELETE)
                .setHeaders(new HeadersMultiMap().add("Authorization", this.adminJiraApiAuthorization)
                        .add("Accept", accept)))
                .flatMap(HttpClientRequest::send)
                .onSuccess(response -> {
                    promise.complete();
                }).onFailure(err -> {
                    log.error(
                            "[Jira-Connector@%s::removeUserFromGroupJira] Failed to remove user from group from Jira API: "
                                    + err.getMessage());
                    promise.fail(err);
                });
        return promise.future();
    }
}
