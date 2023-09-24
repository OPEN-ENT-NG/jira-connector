package fr.openent.jiraconnector.service;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import org.entcore.common.neo4j.Neo4j;

public class ServiceFactory {
    private final Vertx vertx;
    private final Neo4j neo4j;
    private final JiraConnectorConfig jiraConnectorConfig;
    public ServiceFactory(Vertx vertx, Neo4j neo4j, JiraConnectorConfig jiraConnectorConfig) {
        this.vertx = vertx;
        this.neo4j = neo4j;
        this.jiraConnectorConfig = jiraConnectorConfig;
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

}
