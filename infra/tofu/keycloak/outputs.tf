output "realm_id" {
  value = keycloak_realm.realm.id
}

output "browser_client_id" {
  value = keycloak_openid_client.browser.client_id
}
