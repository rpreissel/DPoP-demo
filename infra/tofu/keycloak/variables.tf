variable "keycloak_url" {
  type = string
}

variable "keycloak_admin" {
  type = string
}

variable "keycloak_admin_password" {
  type      = string
  sensitive = true
}

variable "realm_name" {
  type    = string
  default = "dpop-demo"
}

variable "browser_client_id" {
  type    = string
  default = "dpop-demo-web"
}

variable "browser_client_secret" {
  type      = string
  sensitive = true
}

variable "orchestrator_admin_client_secret" {
  type      = string
  sensitive = true
  default   = "change-me-orchestrator-admin"
}
