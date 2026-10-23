package fr.boxtwitch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Petit serveur HTTP local pour la source navigateur de Streamlabs Desktop.
 * Sécurité : écoute UNIQUEMENT sur 127.0.0.1, jeton obligatoire pour lire l'état, contrôle de l'en-tête Host
 * (anti DNS-rebinding), lecture seule (aucune action possible via HTTP).
 */
public final class OverlayServer {
    private HttpServer http;
    private volatile String stateJson = "{}";
    private String token = "";

    public void setState(String json) { this.stateJson = json; }

    public synchronized void start(int port, String token) {
        stop();
        this.token = token;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            http.createContext("/overlay", this::overlay);
            http.createContext("/state", this::state);
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "BoxTwitch-Overlay");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            BoxTwitchMod.LOG.info("Overlay : http://127.0.0.1:{}/overlay?token=…", port);
        } catch (IOException e) {
            BoxTwitchMod.LOG.error("Impossible de démarrer l'overlay sur le port {} (déjà utilisé ?)", port, e);
            http = null;
        }
    }

    public synchronized void stop() {
        if (http != null) { http.stop(0); http = null; }
    }

    public boolean isRunning() { return http != null; }

    private boolean hostOk(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        return h != null && (h.startsWith("127.0.0.1:") || h.startsWith("localhost:"));
    }

    private void overlay(HttpExchange ex) throws IOException {
        if (!hostOk(ex)) { reply(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }
        try (InputStream in = OverlayServer.class.getResourceAsStream("/overlay/overlay.html")) {
            if (in == null) { reply(ex, 404, "text/plain", "overlay introuvable".getBytes(StandardCharsets.UTF_8)); return; }
            reply(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void state(HttpExchange ex) throws IOException {
        if (!hostOk(ex)) { reply(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }
        String q = ex.getRequestURI().getRawQuery();
        String given = "";
        if (q != null) for (String p : q.split("&")) if (p.startsWith("token=")) given = p.substring(6);
        boolean ok = MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) { reply(ex, 403, "text/plain", "jeton invalide".getBytes(StandardCharsets.UTF_8)); return; }
        reply(ex, 200, "application/json; charset=utf-8", stateJson.getBytes(StandardCharsets.UTF_8));
    }

    private void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
