# À propos de l'application Jira-Connector

* Licence : [AGPL v3](http://www.gnu.org/licenses/agpl.txt) - Copyright Région Nouvelle Aquitaine
* Propriétaire(s) : CGI
* Mainteneur(s) : CGI
* Financeur(s) : Région Nouvelle Aquitaine
* Description : Application permettant de la connexion à Jira en SSO depuis l'ENT.

## Configuration du module magneto dans le projet OPEN ENT

Dans le fichier 'ent-core.json.template' du projet OPEN ENT :

<pre>
    {
      "name": "fr.openent~jira-connector~0.1-SNAPSHOT",
      "config": {
        "main" : "fr.openent.jira-connector.Jira",
        "port" : 8206,
        "app-name" : "Jira",
    	"app-address" : "/jira",
        "address": "${jiraConnectorEbAddress}",
        "host": "${host}",
        "ssl" : $ssl,
        "auto-redeploy": false,
        "userbook-host": "${host}",
        "integration-mode" : "HTTP",
        "app-registry.port" : 8012,
        "mode" : "${mode}",
        "jira-groups": ${jiraSsoGroups},
        "uai-admin": ${jiraUaiAdmin}
      }
    }
</pre>

Dans votre springboard, vous devez inclure des variables d'environnement :

| **conf.properties**          | **Utilisation**                                                                         | **Exemple**                                                                                                                              |
|------------------------------|-----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| "${jiraConnectorEbAddress}"  | Addresse de l'event bus permettant la connexion sso à Jira                              | fr.openent.ssojira                                                                                                                       |
| "${jira-groups}"   | Objet contenant les différents groupes Jira et leur correspondance                      | '{"region": "jira-region-ent", "admin": "jira-administrateur-ent","personnel": "jira-personnel-ent","users": "jira-software-users-ent"}' |
| "${uai-admin}"     | UAI de l'établissement région de la plateforme pour désigner les administrateurs région | 9999999K                                                                                                                                 |

Associer une route d'entrée à la configuration du module proxy intégré (`"name": "com.wse~http-proxy~1.0.0"`) :
<pre>
      {
        "location": "/jira",
        "proxy_pass": "http://localhost:8206"
      }
</pre>


## Documentation
Magneto est un outil de création permettant aux utilisateurs de créer et d’échanger des tableaux.
Il contient des aimants, chaque aimant ayant son propre type (texte, image, lien etc..).

# Méthode d'appel de l'event bus
Pour appeler l'event bus, il faut d'abord fournir un JsonObject contenant 3 propriétés :

| **Propriété**  | **Description**                                                | **Exemple**        |
|----------------|----------------------------------------------------------------|--------------------|
| "userId"       | Identifiant de l'utilisateur pour qui on souhaite se connecter | fr.openent.ssojira |
| "host"         | Host de la plateforme                                          | ng2.support-ent.fr |
| "serviceProviderEntityId" | Plateforme Jira utilisée                                       | https://jira-crna-test.support-ent.fr           |

Il suffit ensuite d'appeler l'event bus à l'adresse fourni en variable de configuration de ce module avec la clé jiraConnectorEbAddress. 

# Réponse de l'appel event bus

Pour la réponse, elle se fait sous la forme d'un JsonArray que l'on peut découpé en 2 :
* Les informations sur l'utilisateur avec son login, son mail et son nom d'affichage.
* Les groupes (structures, academies et groupe Jira saisi en conf au préalable) dans lesquels on va l'insérer sur Jira.

Exemple pour un administrateur région:
<pre>
[
   {
      "login":"najwa.kedou"
   },
   {
      "displayName":"Najwa KEDOU"
   },
   {
      "email":"najwa.kedou-1@ng1.support-ent.fr"
   },
   {
      "group":"jira-software-users-ent"
   },
   {
      "group":"0771517F - Etablissement Formation 55452"
   },
   {
      "group":"Académie de CRETEIL"
   },
   {
      "group":"jira-region-ent"
   }
]
</pre>




