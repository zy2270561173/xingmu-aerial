package app.xingmu.aerial.cast;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Wires TLS 1.2 into an OkHttp client for the old TVs. Android 4.4 has TLS 1.2 but leaves it
 * disabled, so an https backend otherwise fails the handshake. Certificates are still validated
 * against the platform trust store - this only widens the allowed protocol list.
 *
 * Written in Java on purpose: Kotlin's view of javax.net.ssl here goes through the Android stubs and
 * the trust-manager accessors are easy to get wrong.
 */
final class Tls12Setup {
    private Tls12Setup() {
    }

    static void install(OkHttpClient.Builder builder) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        List<X509TrustManager> managers = new ArrayList<>();
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                managers.add((X509TrustManager) manager);
            }
        }
        if (managers.isEmpty()) {
            throw new IllegalStateException("no X509 trust manager");
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.toArray(new TrustManager[0]), null);
        builder.sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), managers.get(0));
    }
}
