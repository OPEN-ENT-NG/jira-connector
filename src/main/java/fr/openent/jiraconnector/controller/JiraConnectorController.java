package fr.openent.jiraconnector.controller;

import fr.openent.jiraconnector.Jira;
import fr.openent.jiraconnector.config.JiraConnectorConfig;
import fr.openent.jiraconnector.service.ServiceFactory;
import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import io.vertx.core.http.HttpServerRequest;
import org.entcore.common.controller.ControllerHelper;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;

public class JiraConnectorController extends ControllerHelper {

    private final EventStore eventStore;
    private final JiraConnectorConfig jiraConfig;

    public JiraConnectorController(ServiceFactory serviceFactory) {
        this.jiraConfig = serviceFactory.jiraConnectorConfig();
        this.eventStore = EventStoreFactory.getFactory().getEventStore(Jira.class.getSimpleName());
    }

    @Get("/config")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void getConfig(final HttpServerRequest request) {
        renderJson(request, config.copy());
    }
}
