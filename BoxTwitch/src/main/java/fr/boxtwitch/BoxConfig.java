package fr.boxtwitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration persistante : config/boxtwitch.json */
public class BoxConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("boxtwitch.json");

    /** Commandes de chat qui déclenchent un événement. */
    public static final List<String> EVENT_NAMES = List.of("aide", "bloc", "remplir", "tnt", "pluie", "chance", "chaos");

    // --- Twitch ---
    public String twitchChannel = "";
    public boolean twitchEnabled = true;

    // --- Interactions ---
    public boolean interactionsEnabled = true;
    public boolean tntEnabled = true;
    public Map<String, Boolean> events = new LinkedHashMap<>();
    /** Délai de recharge GLOBAL par commande, en secondes. */
    public Map<String, Integer> cooldowns = new LinkedHashMap<>();
    public int userCooldownSec = 30;      // délai entre 2 événements d'un même viewer
    public int maxEventsPerUser = 0;      // 0 = illimité (par défi)

    // --- Effets ---
    public int blocksPerEvent = 25;
    public int tntPower = 6;              // rayon en blocs
    public int tntCount = 1;
    public int rainBlocks = 60;
    public List<String> fillBlocks = new ArrayList<>(Arrays.asList(
            "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_planks",
            "minecraft:stone_bricks", "minecraft:deepslate", "minecraft:netherrack", "minecraft:white_concrete"));
    public int fillBlockIndex = 0;

    // --- Box (coins opposés, inclus) ---
    public String dimension = "minecraft:overworld";
    public int[] pos1 = null;
    public int[] pos2 = null;

    // --- Droits ---
    /** Noms de joueurs autorisés à ouvrir le menu admin (en plus du propriétaire en solo et de la console). */
    public List<String> admins = new ArrayList<>();

    // --- Overlay Streamlabs ---
    public boolean overlayEnabled = true;
    public int overlayPort = 8765;
    public String overlayToken = "";

    public static BoxConfig load() {
        BoxConfig c = null;
        try {
            if (Files.exists(FILE)) {
                c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), BoxConfig.class);
            }
        } catch (Exception e) {
            BoxTwitchMod.LOG.error("Config illisible, valeurs par défaut utilisées", e);
        }
        if (c == null) c = new BoxConfig();
        c.normalize();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BoxTwitchMod.LOG.error("Impossible d'enregistrer la config", e);
        }
    }

    public void normalize() {
        if (events == null) events = new LinkedHashMap<>();
        if (cooldowns == null) cooldowns = new LinkedHashMap<>();
        Map<String, Integer> defCd = Map.of("aide", 20, "bloc", 15, "remplir", 60, "tnt", 20, "pluie", 45, "chance", 30, "chaos", 120);
        for (String n : EVENT_NAMES) {
            events.putIfAbsent(n, true);
            cooldowns.putIfAbsent(n, defCd.get(n));
        }
        if (fillBlocks == null || fillBlocks.isEmpty()) fillBlocks = new ArrayList<>(List.of("minecraft:stone"));
        if (admins == null) admins = new ArrayList<>();
        if (twitchChannel == null) twitchChannel = "";
        if (dimension == null) dimension = "minecraft:overworld";
        fillBlockIndex = Math.floorMod(fillBlockIndex, fillBlocks.size());
        blocksPerEvent = clamp(blocksPerEvent, 1, 1000);
        tntPower = clamp(tntPower, 2, 30);
        tntCount = clamp(tntCount, 1, 10);
        rainBlocks = clamp(rainBlocks, 5, 500);
        userCooldownSec = clamp(userCooldownSec, 0, 600);
        maxEventsPerUser = clamp(maxEventsPerUser, 0, 1000);
        overlayPort = clamp(overlayPort, 1024, 65535);
        if (overlayToken == null || overlayToken.length() < 12) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            overlayToken = sb.toString();
        }
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
