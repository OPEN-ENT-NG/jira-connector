package fr.openent.jiraconnector.config;

import fr.openent.jiraconnector.core.constants.Field;
import io.vertx.core.json.JsonObject;

public class JiraConnectorConfig {

    private final String ebAddress;
    private final String jiraUaiAdmin;
    private final JiraSsoGroups jiraSsoGroups;

    private final String scimUrl;
    private final String scimToken;
    private final String adminJiraApiUrl;
    private final String adminJiraApiAuthorization;
    private final String adminJiraDirectoryId;

    public JiraConnectorConfig(JsonObject config) {
        this.ebAddress = config.getString(Field.ADDRESS, "fr.openent.ssojira");
        this.jiraSsoGroups = new JiraSsoGroups(config.getJsonObject(Field.JIRA_SSO_GROUPS, new JsonObject()));
        this.jiraUaiAdmin = config.getString(Field.JIRA_UAI_ADMIN, "");
        this.scimUrl = config.getString(Field.SCIM_URL, "");
        this.scimToken = config.getString(Field.SCIM_TOKEN, "");
        this.adminJiraApiUrl = config.getString(Field.ADMIN_JIRA_API_URL, "");
        this.adminJiraApiAuthorization = config.getString(Field.ADMIN_JIRA_API_AUTHORIZATION, "");
        this.adminJiraDirectoryId = config.getString(Field.ADMIN_JIRA_DIRECTORY_ID, "");
    }

    public String ebAddress() {
        return this.ebAddress;
    }

    public JiraSsoGroups jiraSsoGroups() {
        return this.jiraSsoGroups;
    }

    public String getJiraUaiAdmin() {
        return jiraUaiAdmin;
    }

    public String getScimUrl() {
        return scimUrl;
    }

    public String getScimToken() {
        return scimToken;
    }

    public String getAdminJiraApiUrl() {
        return adminJiraApiUrl + "/"+ adminJiraDirectoryId;
    }

    public String getAdminJiraApiAuthorization() {
        return adminJiraApiAuthorization;
    }

  
    public static class JiraSsoGroups {

        private String region;
        private String admin;
        private String personnel;
        private String users;

        public JiraSsoGroups(JsonObject jiraSsoGroups) {
            this.region = jiraSsoGroups.getString("region", "");
            this.admin = jiraSsoGroups.getString("admin", "");
            this.personnel = jiraSsoGroups.getString("personnel", "");
            this.users = jiraSsoGroups.getString("users", "");
        }

        public String getRegion() {
            return region;
        }

        public JiraSsoGroups setRegion(String region) {
            this.region = region;
            return this;
        }

        public String getAdmin() {
            return admin;
        }

        public JiraSsoGroups setAdmin(String admin) {
            this.admin = admin;
            return this;
        }

        public String getPersonnel() {
            return personnel;
        }

        public JiraSsoGroups setPersonnel(String personnel) {
            this.personnel = personnel;
            return this;
        }

        public String getUsers() {
            return users;
        }

        public JiraSsoGroups setUsers(String users) {
            this.users = users;
            return this;
        }
    }
}
