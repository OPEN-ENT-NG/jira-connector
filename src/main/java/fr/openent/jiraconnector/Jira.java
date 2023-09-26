package fr.openent.jiraconnector;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.controller.JiraConnectorController;
import fr.openent.jiraconnector.service.ServiceFactory;
import io.vertx.core.DeploymentOptions;
import org.entcore.common.http.BaseServer;
import org.entcore.common.neo4j.Neo4j;

public class Jira extends BaseServer {

	private ServiceFactory serviceFactory;
	@Override
	public void start() throws Exception {
		super.start();
		JiraConnectorConfig jiraConnectorConfig = new JiraConnectorConfig(config);
		this.serviceFactory = new ServiceFactory(vertx, Neo4j.getInstance(), jiraConnectorConfig);
		addController(new JiraConnectorController(serviceFactory));
		vertx.deployVerticle(SsoJira.class, new DeploymentOptions().setConfig(config));
	}
}
