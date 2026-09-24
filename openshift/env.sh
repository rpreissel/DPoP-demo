# Von deploy.sh und local-up.sh eingebunden: laedt .env aus dem Projektverzeichnis - genau die
# Datei, die auch Podman Compose liest (Vorlage: .env.work.example, die selbst nie gelesen wird).
# So gilt eine Arbeitsplatz-Einstellung wie KEYCLOAK_BASE_IMAGE fuer Compose, den lokalen Test und
# OpenShift gleich. Bewusst keine weitere Datei: was Compose nicht liest, lesen auch diese Skripte nicht.
#
# Vorrang: eine in der Shell gesetzte Variable wird nie ueberschrieben.
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

load_env_file .env
