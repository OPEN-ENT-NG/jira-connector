package fr.openent.jiraconnector;

import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.service.ServiceFactory;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;
import org.vertx.java.busmods.BusModBase;

public class Jira extends BusModBase implements Handler<Message<JsonObject>> {

	private ServiceFactory serviceFactory;
	@Override
	public void start() {
		super.start();
		JiraConnectorConfig jiraConnectorConfig = new JiraConnectorConfig(config);
		ServiceFactory serviceFactory = new ServiceFactory(vertx, Neo4j.getInstance(), jiraConnectorConfig);
		vertx.eventBus().consumer(jiraConnectorConfig.address(), this);
	}

	@Override
	public void handle(Message<JsonObject> jsonObjectMessage) {
		// TODO
		this.serviceFactory.jiraConnectorConfig();

	}
}
