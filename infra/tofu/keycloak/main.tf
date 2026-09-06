terraform {
  required_version = ">= 1.8.0"

  required_providers {
    keycloak = {
      source  = "keycloak/keycloak"
      version = "~> 5.3"
    }
  }
}

provider "keycloak" {
  client_id                = "admin-cli"
  username                 = var.keycloak_admin
  password                 = var.keycloak_admin_password
  url                      = var.keycloak_url
  realm                    = "master"
  initial_login            = false
  tls_insecure_skip_verify = true
}

resource "keycloak_realm" "realm" {
  realm        = var.realm_name
  enabled      = true
  display_name = "DPoP-Demo"
  # orchestrator-select.ftl/orchestrator-tool.ftl (keycloak-extension) leben unter diesem Theme -
  # ohne diese Zuordnung faellt Keycloak auf das Default-Theme zurueck, das die Templates nicht kennt.
  login_theme = "orchestrator"
  # auth-username-password-form (LoA1, natives Login) akzeptiert damit die E-Mail-Adresse als
  # Alternative zum username - der username selbst (KeycloakAdminClient.uniqueUsername) bleibt
  # ohnehin stabil, aber E-Mail ist das, was sich Nutzer tatsaechlich merken.
  login_with_email_allowed = true
}

# Keycloak's declarative User Profile (default since 24.x) silently drops any user attribute not
# declared here - without this, KeycloakAdminClient's orchestratorAccountId attribute (set on every
# synced user, see the account-sync service account below) is accepted by the API but never
# actually persisted, and OrchestratorNotes.accountId(user) always reads null.
# This resource is authoritative for the WHOLE profile, not additive - username/email/first/last
# name must be re-declared here too (same shape Keycloak ships by default), or they silently lose
# their own configuration the moment this resource manages the realm.
resource "keycloak_realm_user_profile" "realm_profile" {
  realm_id = keycloak_realm.realm.id

  attribute {
    name         = "username"
    display_name = "$${username}"
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name         = "email"
    display_name = "$${email}"
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name         = "firstName"
    display_name = "$${firstName}"
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name         = "lastName"
    display_name = "$${lastName}"
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name = "orchestratorAccountId"
    permissions {
      view = ["admin"]
      edit = ["admin"]
    }
  }
}

resource "keycloak_realm_events" "realm_events" {
  realm_id       = keycloak_realm.realm.id
  events_enabled = true
  events_listeners = [
    "jboss-logging",
  ]
}

# The browser-facing RP client (real Web-Kanal demo UI) - deliberately separate from
# keycloak_openid_client.orchestrator_admin below: this one only ever runs the authorization_code
# flow a real browser drives, never a service account, and must never be handed admin rights.
# orchestrator_admin is the exact opposite - a backend-only service account with realm-management
# rights, no browser flow at all. Two different trust levels, two different clients.
#
# PUBLIC + PKCE (S256), not CONFIDENTIAL: the Web-Kanal demo UI exchanges the authorization code
# for tokens directly from the browser (no backend token-exchange proxy) - a confidential client's
# secret would otherwise have to ship inside the browser bundle, defeating the point of having one.
resource "keycloak_openid_client" "browser" {
  realm_id                    = keycloak_realm.realm.id
  client_id                   = var.browser_client_id
  name                        = var.browser_client_id
  access_type                 = "PUBLIC"
  standard_flow_enabled       = true
  direct_access_grants_enabled = false
  service_accounts_enabled    = false
  valid_redirect_uris         = ["http://localhost:8080/*", "http://localhost:5173/*"]
  web_origins                 = ["http://localhost:8080", "http://localhost:5173"]
  pkce_code_challenge_method  = "S256"

  authentication_flow_binding_overrides {
    browser_id = keycloak_authentication_flow.orchestrator_browser.id
  }
}

# The client picking up OrchestratorAcrAmrMapper.PROVIDER_ID from a dedicated default client scope
# rather than the client directly, so the same behaviour is trivially reusable by any later client.
resource "keycloak_openid_client_scope" "orchestrator_claims_scope" {
  realm_id = keycloak_realm.realm.id
  name     = "orchestrator-claims"
}

resource "keycloak_generic_protocol_mapper" "orchestrator_acr_amr" {
  realm_id        = keycloak_realm.realm.id
  client_scope_id = keycloak_openid_client_scope.orchestrator_claims_scope.id
  name            = "orchestrator-acr-amr"
  protocol        = "openid-connect"
  protocol_mapper = "orchestrator-acr-amr-mapper"
  # AbstractOIDCProtocolMapper (keycloak-extension's own base class) skips setClaim entirely unless
  # these two keys are literally "true" - not a real config choice for this mapper, just what the
  # base class's own gate requires to be present.
  config = {
    "access.token.claim" = "true"
    "id.token.claim"     = "true"
  }
}

# Lets the orchestrator resolve accountId directly from a real AccessToken's own claims
# (KeycloakOidcTokenValidator, demo-only Web-Kanal Journey-Log read path) instead of having to
# reverse-engineer it via the Keycloak session id - a built-in mapper type, no custom Java needed,
# reading the SAME orchestratorAccountId user attribute account-sync already writes.
resource "keycloak_openid_user_attribute_protocol_mapper" "orchestrator_account_id" {
  realm_id            = keycloak_realm.realm.id
  client_scope_id     = keycloak_openid_client_scope.orchestrator_claims_scope.id
  name                = "orchestrator-account-id"
  user_attribute      = "orchestratorAccountId"
  claim_name          = "orchestrator_account_id"
  claim_value_type    = "String"
  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}

resource "keycloak_openid_client_default_scopes" "browser_default_scopes" {
  realm_id  = keycloak_realm.realm.id
  client_id = keycloak_openid_client.browser.id
  default_scopes = [
    "profile",
    "email",
    "roles",
    "web-origins",
    keycloak_openid_client_scope.orchestrator_claims_scope.name,
  ]
}

# ── Browser flow: resume the channel, cookie SSO reuse, then per-LoA-level content ────────────────
# Structure follows docs/ideen/web-keycloak-kanal.md #9: the native Condition-LoA subflow structure
# stays (Keycloak alone decides IF/WHICH level to enter). LoA1 is native Keycloak password (reported
# to the orchestrator right after, see orchestrator-update-authenticator below); LoA2 is the
# orchestrator's own selectMethod, offering whichever OTHER active methods this account has -
# CandidateTools.forAuth already excludes anything evidence.amr already covers, "password" included
# once LoA1's report reaches it.

resource "keycloak_authentication_flow" "orchestrator_browser" {
  realm_id    = keycloak_realm.realm.id
  alias       = "orchestrator-browser"
  description = "Browser flow with LoA conditions driven by the orchestrator's kc-facade"
  provider_id = "basic-flow"
}

# Wrapped in its own (otherwise pointless) subflow purely so Keycloak's AuthenticationFlowCallback
# mechanism applies to it at all: onTopFlowSuccess only ever fires for an execution whose factory
# was registered via DefaultAuthenticationFlow.checkAuthCallback - which only runs when the
# execution's OWN PARENT completes as a SUBFLOW (isAuthenticatorFlow()), never for an execution
# sitting directly in the top-level flow. Once wrapped, onTopFlowSuccess fires at the true end of
# the WHOLE top flow (not this tiny wrapper) - see OrchestratorResumeAuthenticator's own doc.
resource "keycloak_authentication_subflow" "orchestrator_resume_wrapper" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.orchestrator_browser.alias
  alias             = "orchestrator-resume-wrapper"
  description       = "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers"
  provider_id       = "basic-flow"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution" "orchestrator_resume" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_resume_wrapper.alias
  authenticator     = "orchestrator-resume-authenticator"
  requirement       = "REQUIRED"
  priority          = 10
}

# Keycloak refuses to mix REQUIRED and ALTERNATIVE at the same flow level (silently drops the
# ALTERNATIVE branch instead of erroring - "REQUIRED and ALTERNATIVE elements at same level!" in
# the log) - same shape the built-in "browser" flow itself uses for this reason: a REQUIRED step,
# then a nested subflow that is itself REQUIRED at this level but holds the actual ALTERNATIVE
# group (cookie vs. interactive auth) inside.
resource "keycloak_authentication_subflow" "orchestrator_browser_forms" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.orchestrator_browser.alias
  alias             = "orchestrator-browser-forms"
  description       = "Cookie SSO reuse vs. interactive auth, tried as alternatives"
  provider_id       = "basic-flow"
  requirement       = "REQUIRED"
  priority          = 20
}

resource "keycloak_authentication_execution" "browser_cookie" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_browser_forms.alias
  authenticator     = "auth-cookie"
  requirement       = "ALTERNATIVE"
  priority          = 10
}

resource "keycloak_authentication_subflow" "orchestrator_auth_flow" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_browser_forms.alias
  alias             = "orchestrator-auth-flow"
  description       = "LoA-aware branches, each driven by the OrchestratorAuthenticator"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  priority          = 20
}

# ── LoA 1: native Keycloak username/password (accountId lives on the Keycloak user attribute
# OrchestratorNotes.USER_ATTR_ACCOUNT_ID, kept in sync by KeycloakAccountSyncListener - see the
# account-sync service account further below) - reported to the orchestrator right afterward so
# LoA-2's step-up candidates already exclude "password".

resource "keycloak_authentication_subflow" "orchestrator_loa_1" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_auth_flow.alias
  alias             = "orchestrator-loa-1"
  description       = "LoA-1 branch: native Keycloak username/password"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 10
}

resource "keycloak_authentication_execution" "loa_1_condition" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_loa_1.alias
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "loa_1_condition" {
  realm_id     = keycloak_realm.realm.id
  execution_id = keycloak_authentication_execution.loa_1_condition.id
  alias        = "orchestrator-loa-1-condition"

  config = {
    "loa-condition-level" = "1"
    "loa-max-age"         = "36000"
  }
}

resource "keycloak_authentication_execution" "loa_1_password" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_loa_1.alias
  authenticator     = "auth-username-password-form"
  requirement       = "REQUIRED"
  priority          = 20
}

resource "keycloak_authentication_execution" "loa_1_report_password" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_loa_1.alias
  authenticator     = "orchestrator-update-authenticator"
  requirement       = "REQUIRED"
  priority          = 30
}

resource "keycloak_authentication_execution_config" "loa_1_report_password" {
  realm_id     = keycloak_realm.realm.id
  execution_id = keycloak_authentication_execution.loa_1_report_password.id
  alias        = "orchestrator-loa-1-report-password"

  # Matches NativeAuthenticatorRegistry.kt's "kc-password-form" entry exactly - that registry, not
  # this config, is where method/maxAcr/factorTypes for this id are actually resolved.
  config = {
    "nativeToolId" = "kc-password-form"
  }
}

# ── LoA 2: step-up, account-specific candidates only ──────────────────────────────────────────────

resource "keycloak_authentication_subflow" "orchestrator_loa_2" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_auth_flow.alias
  alias             = "orchestrator-loa-2"
  description       = "LoA-2 branch: orchestrator step-up (account-specific candidates)"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 20
}

resource "keycloak_authentication_execution" "loa_2_condition" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_loa_2.alias
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "loa_2_condition" {
  realm_id     = keycloak_realm.realm.id
  execution_id = keycloak_authentication_execution.loa_2_condition.id
  alias        = "orchestrator-loa-2-condition"

  config = {
    "loa-condition-level" = "2"
    "loa-max-age"         = "36000"
  }
}

resource "keycloak_authentication_execution" "loa_2_orchestrator" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.orchestrator_loa_2.alias
  authenticator     = "orchestrator-authenticator"
  requirement       = "REQUIRED"
  priority          = 20
}

resource "keycloak_authentication_execution_config" "loa_2_orchestrator" {
  realm_id     = keycloak_realm.realm.id
  execution_id = keycloak_authentication_execution.loa_2_orchestrator.id
  alias        = "orchestrator-loa-2-orchestrator"

  # The LoA-to-ACR mapping table docs/ideen/web-keycloak-kanal.md #9 asks for, reduced to what one
  # execution needs: its OWN numeric level's orchestrator-ACR translation, one config value per
  # Condition-LoA subflow rather than a realm-wide lookup table.
  config = {
    "targetAcr" = "loa2"
  }
}

# ── Account-Sync service account ──────────────────────────────────────────────────────────────────
# No more hand-declared demo users here: KeycloakAdminClient/KeycloakAccountSyncListener (orchestrator,
# `keycloak` profile only) mirror every AccountService create/change/delete into a Keycloak user via
# the Admin REST API, authenticated as THIS client's own service account - the standard Keycloak
# pattern for a backend managing users without a human admin session.
resource "keycloak_openid_client" "orchestrator_admin" {
  realm_id                     = keycloak_realm.realm.id
  client_id                    = "orchestrator-admin"
  name                         = "orchestrator-admin"
  access_type                  = "CONFIDENTIAL"
  standard_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = true
  client_secret                = var.orchestrator_admin_client_secret
}

# Built into every realm - never created by this config, just referenced for its "manage-users" role.
data "keycloak_openid_client" "realm_management" {
  realm_id  = keycloak_realm.realm.id
  client_id = "realm-management"
}

resource "keycloak_openid_client_service_account_role" "orchestrator_admin_manage_users" {
  realm_id                = keycloak_realm.realm.id
  service_account_user_id = keycloak_openid_client.orchestrator_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = "manage-users"
}

# Needed for KeycloakAdminClient.passwordStorageComponentId() (DPoP-demo-25q) - GET
# .../components is guarded by the realm's general "view realm" permission, not manage-users.
resource "keycloak_openid_client_service_account_role" "orchestrator_admin_view_realm" {
  realm_id                = keycloak_realm.realm.id
  service_account_user_id = keycloak_openid_client.orchestrator_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = "view-realm"
}

# Same "orchestrator-claims" scope the browser client already gets (see browser_default_scopes) -
# without it, OrchestratorAcrAmrMapper never runs for the custom account-token grant's own tokens
# (DPoP-demo-xso), since that grant authenticates as THIS client, not the browser one.
resource "keycloak_openid_client_default_scopes" "orchestrator_admin_default_scopes" {
  realm_id  = keycloak_realm.realm.id
  client_id = keycloak_openid_client.orchestrator_admin.id
  default_scopes = [
    "profile",
    "email",
    "roles",
    keycloak_openid_client_scope.orchestrator_claims_scope.name,
  ]
}

# ── Native password credential -> orchestrator's own auth_password store (DPoP-demo-25q) ──────────

# Routes the "password" credential type for every federation-linked user to
# OrchestratorPasswordStorageProvider instead of Keycloak's built-in JPA password provider - no
# password is ever stored in Keycloak itself, same principle as an LDAP federation provider
# delegating to its directory (docs/ideen/web-keycloak-kanal.md). KeycloakAdminClient.createUser
# looks this component up by provider_id (its own id varies per environment/import, so it can't be
# hardcoded there) and sets it as every newly synced user's federationLink.
resource "keycloak_custom_user_federation" "orchestrator_password" {
  name        = "orchestrator-password"
  realm_id    = keycloak_realm.realm.id
  provider_id = "orchestrator-password"
  enabled     = true
  priority    = 0
}
