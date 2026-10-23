package fr.boxtwitch;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Lecture du chat Twitch en LECTURE SEULE via IRC (TLS, port 6697) avec un compte anonyme "justinfan".
 * -> aucun mot de passe, aucun jeton OAuth, rien de sensible à stocker.
 * Les messages ne sont JAMAIS exécutés comme commandes Minecraft : seuls les mots-clés de la liste blanche
 * sont retransmis (file d'attente), puis traités sur le thread serveur.
 */
public final class TwitchChat {
    public record ChatMsg(String user, String cmd) {}

    private static final Set<String> ALLOWED = Set.of(
            "aide", "bloc", "remplir", "tnt", "pluie", "chance", "chaos", "progression", "top");
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_]{3,25}");

    public final ConcurrentLinkedQueue<ChatMsg> queue = new ConcurrentLinkedQueue<>();
    public volatile String status = "arrêté";
    public volatile boolean connected = false;

    private volatile boolean running = false;
    private volatile Socket socket;
    private Thread thread;
    private Writer out;

    public synchronized void start(String channelRaw) {
        stop();
        String channel = channelRaw == null ? "" : channelRaw.trim().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
        if (!CHANNEL.matcher(channel).matches()) {
            status = channel.isEmpty() ? "aucun salon configuré" : "nom de salon invalide";
            return;
        }
        running = true;
        thread = new Thread(() -> loop(channel), "BoxTwitch-IRC");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        connected = false;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        if (thread != null) thread.interrupt();
        thread = null;
        status = "arrêté";
    }

    private void loop(String channel) {
        int backoff = 3000;
        while (running) {
            try (Socket s = SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697)) {
                socket = s;
                s.setSoTimeout(330_000);
                out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                status = "connexion…";
                send("CAP REQ :twitch.tv/tags");
                send("PASS SCHMOOPIIE"); // mot de passe public prévu pour les connexions anonymes
                send("NICK justinfan" + (10000 + new SecureRandom().nextInt(80000)));
                send("JOIN #" + channel);
                String line;
                while (running && (line = in.readLine()) != null) {
                    handle(line, channel);
                    backoff = 3000;
                }
                if (running) status = "déconnecté, nouvelle tentative…";
            } catch (Exception e) {
                if (running) status = "erreur réseau : " + e.getClass().getSimpleName();
            }
            connected = false;
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private synchronized void send(String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    private void handle(String line, String channel) throws IOException {
        String tags = "";
        String l = line;
        if (l.startsWith("@")) {
            int sp = l.indexOf(' ');
            if (sp < 0) return;
            tags = l.substring(1, sp);
            l = l.substring(sp + 1);
        }
        if (l.startsWith("PING")) { send("PONG :tmi.twitch.tv"); return; }
        if (!l.startsWith(":")) return;
        int sp = l.indexOf(' ');
        if (sp < 0) return;
        String prefix = l.substring(1, sp);
        String rest = l.substring(sp + 1);

        if (rest.startsWith("366")) { connected = true; status = "connecté à #" + channel; return; }
        if (!rest.startsWith("PRIVMSG ")) return;
        int c = rest.indexOf(" :");
        if (c < 0) return;
        String text = rest.substring(c + 2).replaceAll("\\p{Cf}", "").trim(); // retire caractères invisibles (7TV…)
        if (text.length() > 200 || !text.startsWith("!")) return;
        String token = text.split("\\s+", 2)[0].substring(1).toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(token)) return;

        String nick = prefix.contains("!") ? prefix.substring(0, prefix.indexOf('!')) : prefix;
        String display = tag(tags, "display-name");
        String user = sanitize(display.isEmpty() ? nick : display);
        if (user.isEmpty()) user = sanitize(nick);
        if (user.isEmpty()) return;
        if (queue.size() < 500) queue.add(new ChatMsg(user, token)); // anti-flood
    }

    private static String tag(String tags, String key) {
        for (String t : tags.split(";")) {
            if (t.startsWith(key + "=")) return t.substring(key.length() + 1);
        }
        return "";
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[^\\p{L}\\p{N}_]", "");
        return r.length() > 25 ? r.substring(0, 25) : r;
    }
}
