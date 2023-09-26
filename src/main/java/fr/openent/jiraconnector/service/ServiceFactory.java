package fr.openent.jiraconnector.service;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.service.impl.DefaultSsoJiraService;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import org.entcore.common.neo4j.Neo4j;

public class ServiceFactory {
    private final Vertx vertx;
    private final Neo4j neo4j;
    private final JiraConnectorConfig jiraConnectorConfig;

    private final SsoService ssoService;
    public ServiceFactory(Vertx vertx, Neo4j neo4j, JiraConnectorConfig jiraConnectorConfig) {
        this.vertx = vertx;
        this.neo4j = neo4j;
        this.jiraConnectorConfig = jiraConnectorConfig;
        this.ssoService = new DefaultSsoJiraService(this);
    }

    // Helpers
    public EventBus eventBus() {
        return this.vertx.eventBus();
    }

    public Vertx vertx() {
        return this.vertx;
    }
    public JiraConnectorConfig jiraConnectorConfig() {
        return this.jiraConnectorConfig;
    }

    public SsoService ssoService() {
        return this.ssoService;
    }

}
