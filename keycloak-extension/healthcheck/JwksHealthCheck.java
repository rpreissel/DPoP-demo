import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// Kein curl/wget/openssl in diesem Image, aber ein voller JDK (jdk.compiler-Modul vorhanden) -
// `java JwksHealthCheck.java` kompiliert und startet dieses Single-File-Programm bei jedem
// Healthcheck-Tick neu (JEP 330), ohne javac oder eine dauerhaft laufende Klasse zu brauchen.
//
// Fragt statt eines reinen TCP-Connects gezielt das JWKS des Master-Realms ab (immer vorhanden,
// unauthentifiziert, kein eigener Realm-Import noetig) - ein erfolgreicher 200er mit "keys" im
// Body beweist, dass Keycloaks komplette REST-Schicht (nicht nur der TCP-Listener) tatsaechlich
// Anfragen bedient. Trust-all, weil das selbstsignierte Dev-Zertifikat (siehe Dockerfile) nicht
// von einer echten CA stammt - Hostname-Verifikation bleibt an, das Zertifikat deckt 127.0.0.1
// per SAN ab.
public class JwksHealthCheck {
    public static void main(String[] args) throws Exception {
        TrustManager[] trustAll = {
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());

        HttpClient client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://127.0.0.1:8443/realms/master/protocol/openid-connect/certs"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        boolean ready = response.statusCode() == 200 && response.body().contains("\"keys\"");
        System.exit(ready ? 0 : 1);
    }
}
