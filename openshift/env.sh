# Von deploy.sh und local-up.sh eingebunden: laedt .env und .env.local aus dem Projektverzeichnis
# - dieselben Dateien, die Podman Compose liest (Vorlage: .env.work.example). So gilt eine
# Arbeitsplatz-Einstellung wie KEYCLOAK_BASE_IMAGE fuer Compose, den lokalen Test und OpenShift.
#
# Vorrang: in der Shell gesetzt > .env.local > .env. Eine schon gesetzte Variable wird nie
# ueberschrieben; deshalb wird .env.local zuerst gelesen.
# Format wie in der Vorlage: KEY=value je Zeile, ohne Anfuehrungszeichen; # leitet Kommentare ein.
load_env_file() {
  local file=$1 line key
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in '' | '#'*) continue ;; esac
    key=${line%%=*}
    [ -n "${!key+x}" ] && continue
    export "$line"
  done < "$file"
}

load_env_file .env.local
load_env_file .env
