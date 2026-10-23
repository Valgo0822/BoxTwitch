package fr.boxtwitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Toute la logique : box, comptage, événements, cooldowns, victoire, état pour GUI / overlay. */
public final class BoxManager {
    public enum Status { IDLE, RUNNING, PAUSED, WON }

    public static final BoxManager I = new BoxManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATE_FILE = FabricLoader.getInstance().getConfigDir().resolve("boxtwitch-state.json");

    public record EventLog(long id, String user, String type, String text) {}
    public static class Viewer { public long score; public int events; }
    private static class Saved {
        String status = "IDLE";
        String lastUser = "";
        Map<String, Viewer> viewers = new LinkedHashMap<>();
    }
    private static class Task { int ticks; final Runnable run; Task(int t, Runnable r) { ticks = t; run = r; } }

    public BoxConfig cfg;
    public MinecraftServer server;
    public final TwitchChat twitch = new TwitchChat();
    public final OverlayServer overlay = new OverlayServer();

    private final Random rnd = new Random();
    private Status status = Status.IDLE;
    private String lastUser = "";
    private Map<String, Viewer> viewers = new LinkedHashMap<>();
    private final Deque<EventLog> events = new ArrayDeque<>();
    private final Deque<EventLog> chat = new ArrayDeque<>();
    private long nextId = 1;
    private final List<Task> tasks = new ArrayList<>();
    private final Map<String, Long> lastByCmd = new HashMap<>();
    private final Map<String, Long> lastByUser = new HashMap<>();
    private long lastInfoMs = 0;
    private String cachedJson = "{}";

    // géométrie (coins inclus)
    private boolean valid;
    private int minX, minY, minZ, maxX, maxY, maxZ, ix, iy, iz;
    private long volume, filled;
    private int[] colCount;
    private int cursor;

    // ---------------------------------------------------------------- cycle de vie

    public void start(MinecraftServer s) {
        server = s;
        cfg = BoxConfig.load();
        loadSaved();
        refreshBox();
        if (cfg.overlayEnabled) overlay.start(cfg.overlayPort, cfg.overlayToken);
        if (cfg.twitchEnabled) twitch.start(cfg.twitchChannel);
        BoxTwitchMod.LOG.info("BoxTwitch prêt. URL overlay : {}", overlayUrl());
    }

    public void stop() {
        twitch.stop();
        overlay.stop();
        saveSaved();
        if (cfg != null) cfg.save();
        server = null;
    }

    public String overlayUrl() {
        return "http://127.0.0.1:" + cfg.overlayPort + "/overlay?token=" + cfg.overlayToken;
    }

    // ---------------------------------------------------------------- tick

    public void tick(MinecraftServer s) {
        if (server == null || cfg == null) return;
        int t = s.getTicks();

        // tâches différées (fuse TNT, pluie, feu d'artifice)
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task k = it.next();
            if (--k.ticks <= 0) { it.remove(); try { k.run.run(); } catch (Exception e) { BoxTwitchMod.LOG.error("tâche", e); } }
        }

        // messages Twitch -> thread serveur
        TwitchChat.ChatMsg m;
        int guard = 0;
        while (guard++ < 20 && (m = twitch.queue.poll()) != null) dispatch(m.user(), m.cmd(), false);

        scan();

        if (status == Status.RUNNING && valid && volume > 0 && filled >= volume) victory();

        if (t % 10 == 0) {
            cachedJson = buildJson();
            overlay.setState(cachedJson);
        }
        if (t % 20 == 0) pushState(false);
        if (t % 1200 == 0) saveSaved();
    }

    // ---------------------------------------------------------------- géométrie

    public void refreshBox() {
        valid = false; colCount = null; filled = 0; cursor = 0; volume = 0;
        if (cfg.pos1 == null || cfg.pos2 == null) return;
        minX = Math.min(cfg.pos1[0], cfg.pos2[0]); maxX = Math.max(cfg.pos1[0], cfg.pos2[0]);
        minY = Math.min(cfg.pos1[1], cfg.pos2[1]); maxY = Math.max(cfg.pos1[1], cfg.pos2[1]);
        minZ = Math.min(cfg.pos1[2], cfg.pos2[2]); maxZ = Math.max(cfg.pos1[2], cfg.pos2[2]);
        if (maxX - minX < 2 || maxY - minY < 2 || maxZ - minZ < 2) return; // il faut au moins 3x3x3
        ix = maxX - minX - 1; iy = maxY - minY - 1; iz = maxZ - minZ - 1;
        volume = (long) ix * iy * iz;
        if ((long) ix * iz > 4_000_000L) return;
        colCount = new int[ix * iz];
        valid = true;
    }

    public boolean isValid() { return valid; }
    public long volume() { return volume; }

    private boolean inBounds(BlockPos p) {
        return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    private boolean isInterior(BlockPos p) {
        return p.getX() > minX && p.getX() < maxX && p.getY() > minY && p.getY() < maxY
                && p.getZ() > minZ && p.getZ() < maxZ;
    }

    /** Vrai si la position fait partie de la coque (fond, murs, rebord). */
    public boolean isShell(ServerWorld w, BlockPos p) {
        return valid && w.getRegistryKey().getValue().toString().equals(cfg.dimension) && inBounds(p) && !isInterior(p);
    }

    private ServerWorld world() {
        if (server == null) return null;
        Identifier id = Identifier.tryParse(cfg.dimension);
        if (id == null) return null;
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    public String setCorner(int which, ServerWorld w, BlockPos p) {
        cfg.dimension = w.getRegistryKey().getValue().toString();
        int[] v = {p.getX(), p.getY(), p.getZ()};
        if (which == 1) cfg.pos1 = v; else cfg.pos2 = v;
        cfg.save();
        refreshBox();
        return valid
                ? "Coin " + which + " enregistré. Volume intérieur : " + volume + " blocs (" + ix + "×" + iy + "×" + iz + ")."
                : "Coin " + which + " enregistré. (La box doit faire au moins 3×3×3 pour être valide.)";
    }

    public String setSize(ServerWorld w, BlockPos origin, int sx, int sy, int sz) {
        cfg.dimension = w.getRegistryKey().getValue().toString();
        cfg.pos1 = new int[]{origin.getX(), origin.getY(), origin.getZ()};
        cfg.pos2 = new int[]{origin.getX() + sx - 1, origin.getY() + sy - 1, origin.getZ() + sz - 1};
        cfg.save();
        refreshBox();
        return valid ? "Box définie. Volume intérieur : " + volume + " blocs." : "Taille trop petite (minimum 3×3×3) ou trop grande.";
    }

    /** Construit fond + 4 murs + rebord en bedrock. */
    public String build() {
        ServerWorld w = world();
        if (w == null || !valid) return "Définis d'abord la box (/box pos1, /box pos2 ou /box size).";
        long shell = (long) (maxX - minX + 1) * (maxZ - minZ + 1)
                + (long) (maxY - minY) * 2L * ((maxX - minX + 1) + (maxZ - minZ - 1));
        if (shell > 600_000L) return "Box trop grande pour être construite d'un coup (" + shell + " blocs de coque).";
        BlockState bed = Blocks.BEDROCK.getDefaultState();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                w.setBlockState(p.set(x, minY, z), bed, Block.NOTIFY_LISTENERS);
        for (int y = minY + 1; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                w.setBlockState(p.set(x, y, minZ), bed, Block.NOTIFY_LISTENERS);
                w.setBlockState(p.set(x, y, maxZ), bed, Block.NOTIFY_LISTENERS);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                w.setBlockState(p.set(minX, y, z), bed, Block.NOTIFY_LISTENERS);
                w.setBlockState(p.set(maxX, y, z), bed, Block.NOTIFY_LISTENERS);
            }
        }
        refreshBox();
        return "Box construite ! Volume à remplir : " + volume + " blocs.";
    }

    /** Recomptage incrémental : colonne par colonne, uniquement dans les chunks chargés. */
    private void scan() {
        if (!valid) return;
        ServerWorld w = world();
        if (w == null) return;
        int cols = ix * iz;
        int budget = Math.max(1, 40_000 / iy);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int i = 0; i < budget && i < cols; i++) {
            int c = cursor;
            cursor = (cursor + 1) % cols;
            int x = minX + 1 + (c % ix);
            int z = minZ + 1 + (c / ix);
            if (!w.isChunkLoaded(p.set(x, minY + 1, z))) continue;
            int n = 0;
            for (int y = minY + 1; y < maxY; y++) {
                if (!w.getBlockState(p.set(x, y, z)).isAir()) n++;
            }
            filled += n - colCount[c];
            colCount[c] = n;
        }
    }

    // ---------------------------------------------------------------- état du défi

    public Status status() { return status; }

    public String setStatus(Status s) {
        if (!valid && s == Status.RUNNING) return "La box n'est pas définie.";
        status = s;
        saveSaved();
        pushState(false);
        return switch (s) {
            case RUNNING -> "Défi en cours !";
            case PAUSED -> "Défi en pause.";
            case IDLE -> "Défi arrêté.";
            case WON -> "Défi gagné !";
        };
    }

    public String resetChallenge() {
        status = Status.IDLE;
        viewers.clear();
        events.clear();
        chat.clear();
        lastUser = "";
        lastByCmd.clear();
        lastByUser.clear();
        saveSaved();
        pushState(false);
        return "Défi réinitialisé (classement et compteurs remis à zéro, blocs conservés).";
    }

    public String clearInterior() {
        ServerWorld w = world();
        if (w == null || !valid) return "Box non définie.";
        BlockPos.Mutable p = new BlockPos.Mutable();
        long n = 0;
        for (int x = minX + 1; x < maxX; x++)
            for (int y = minY + 1; y < maxY; y++)
                for (int z = minZ + 1; z < maxZ; z++) {
                    if (!w.isChunkLoaded(p.set(x, y, z))) continue;
                    if (!w.getBlockState(p).isAir()) { w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS); n++; }
                }
        return n + " blocs retirés de l'intérieur.";
    }

    private void victory() {
        status = Status.WON;
        saveSaved();
        log("systeme", "VICTOIRE", "🎉 La box est remplie à 100 % !");
        ServerWorld w = world();
        if (server != null) {
            for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                pl.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("VICTOIRE !").formatted(Formatting.GOLD, Formatting.BOLD)));
            }
            server.getPlayerManager().broadcast(Text.literal("🎉 La box est remplie à 100 % ! Bravo au chat !").formatted(Formatting.GOLD), false);
        }
        if (w == null) return;
        double cx = (minX + maxX) / 2.0 + 0.5, cz = (minZ + maxZ) / 2.0 + 0.5;
        for (int i = 0; i < 40; i++) {
            final int k = i;
            tasks.add(new Task(i * 5, () -> {
                double x = minX + 1 + rnd.nextDouble() * Math.max(1, ix), z = minZ + 1 + rnd.nextDouble() * Math.max(1, iz);
                double y = maxY + 2 + rnd.nextDouble() * 12;
                w.spawnParticles(ParticleTypes.FIREWORK, x, y, z, 60, 3, 3, 3, 0.15);
                w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 30, 2, 2, 2, 0.4);
                w.playSound(null, BlockPos.ofFloored(x, y, z), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.BLOCKS, 3f, 0.8f + rnd.nextFloat() * 0.4f);
                if (k % 10 == 0) w.playSound(null, BlockPos.ofFloored(cx, maxY, cz), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 4f, 1f);
            }));
        }
    }

    // ---------------------------------------------------------------- événements

    private BlockState fillState() {
        Identifier id = Identifier.tryParse(cfg.fillBlocks.get(Math.floorMod(cfg.fillBlockIndex, cfg.fillBlocks.size())));
        Block b = id == null ? Blocks.STONE : Registries.BLOCK.get(id);
        if (b == Blocks.AIR) b = Blocks.STONE;
        return b.getDefaultState();
    }

    /** Trouve la position libre la plus basse au-dessus du "sol" de la colonne, ou -1 si colonne pleine. */
    private int landingY(ServerWorld w, int x, int z, BlockPos.Mutable p) {
        for (int y = maxY - 1; y > minY; y--) {
            if (!w.getBlockState(p.set(x, y, z)).isAir()) return (y + 1 <= maxY - 1) ? y + 1 : -1;
        }
        return minY + 1;
    }

    private int randomX() { return minX + 1 + rnd.nextInt(ix); }
    private int randomZ() { return minZ + 1 + rnd.nextInt(iz); }

    private int placeRandom(ServerWorld w, int n) {
        BlockState st = fillState();
        BlockPos.Mutable p = new BlockPos.Mutable();
        int placed = 0;
        for (int a = 0; a < n * 4 && placed < n; a++) {
            int x = randomX(), z = randomZ();
            if (!w.isChunkLoaded(p.set(x, minY + 1, z))) continue;
            int y = landingY(w, x, z, p);
            if (y < 0) continue;
            w.setBlockState(p.set(x, y, z), st, Block.NOTIFY_ALL);
            if (placed % 6 == 0) w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, x + .5, y + .5, z + .5, 4, .3, .3, .3, 0);
            placed++;
        }
        if (placed > 0) w.playSound(null, BlockPos.ofFloored((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 2f, 1f);
        return placed;
    }

    /** Remplit les couches les plus basses d'abord (bonus de remplissage). */
    private int fillLowest(ServerWorld w, int n) {
        BlockState st = fillState();
        BlockPos.Mutable p = new BlockPos.Mutable();
        int placed = 0, scanned = 0;
        outer:
        for (int y = minY + 1; y < maxY; y++)
            for (int z = minZ + 1; z < maxZ; z++)
                for (int x = minX + 1; x < maxX; x++) {
                    if (++scanned > 600_000) break outer;
                    if (!w.isChunkLoaded(p.set(x, y, z))) continue;
                    if (w.getBlockState(p).isAir()) {
                        w.setBlockState(p, st, Block.NOTIFY_ALL);
                        if (++placed >= n) break outer;
                    }
                }
        return placed;
    }

    private void rain(ServerWorld w, int total) {
        int waves = 30, per = Math.max(1, (int) Math.ceil(total / (double) waves));
        BlockState st = fillState();
        for (int i = 0; i < waves; i++) {
            tasks.add(new Task(i * 2, () -> {
                BlockPos.Mutable p = new BlockPos.Mutable();
                for (int k = 0; k < per; k++) {
                    int x = randomX(), z = randomZ();
                    if (!w.isChunkLoaded(p.set(x, minY + 1, z))) continue;
                    int y = landingY(w, x, z, p);
                    if (y < 0) continue;
                    w.spawnParticles(ParticleTypes.CLOUD, x + .5, maxY - 0.5, z + .5, 3, .2, .2, .2, 0.02);
                    w.setBlockState(p.set(x, y, z), st, Block.NOTIFY_ALL);
                }
                w.playSound(null, BlockPos.ofFloored((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.5f, 0.8f);
            }));
        }
    }

    /** TNT "simulée" : mèche visible, puis suppression des seuls blocs de l'INTÉRIEUR. La coque n'est jamais touchée. */
    private void tnt(ServerWorld w, int power, int delay) {
        int x = randomX(), z = randomZ();
        BlockPos.Mutable p = new BlockPos.Mutable();
        int y = Math.max(minY + 1, Math.min(maxY - 1, landingY(w, x, z, p) < 0 ? maxY - 1 : landingY(w, x, z, p) - 1));
        final BlockPos center = new BlockPos(x, y, z);
        for (int t = 0; t < 40; t += 4) {
            tasks.add(new Task(delay + t, () -> {
                w.spawnParticles(ParticleTypes.SMOKE, center.getX() + .5, center.getY() + 1.2, center.getZ() + .5, 6, .2, .2, .2, 0.01);
                w.spawnParticles(ParticleTypes.FLAME, center.getX() + .5, center.getY() + 1.0, center.getZ() + .5, 3, .1, .1, .1, 0.01);
            }));
        }
        tasks.add(new Task(delay + 1, () -> w.playSound(null, center, SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.BLOCKS, 3f, 1f)));
        tasks.add(new Task(delay + 40, () -> explode(w, center, power)));
    }

    private void explode(ServerWorld w, BlockPos c, int r) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!isInterior(p)) continue;                       // protection de la coque
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > r * (0.75 + 0.25 * rnd.nextDouble())) continue;
                    if (!w.isChunkLoaded(p)) continue;
                    BlockState s = w.getBlockState(p);
                    if (s.isAir() || s.isOf(Blocks.BEDROCK)) continue;
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
        double x = c.getX() + .5, y = c.getY() + .5, z = c.getZ() + .5;
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 3, r * .35, r * .35, r * .35, 0);
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 80, r * .5, r * .5, r * .5, 0.05);
        w.spawnParticles(ParticleTypes.LAVA, x, y, z, 30, r * .4, r * .4, r * .4, 0);
        w.playSound(null, c, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 5f, 0.7f + rnd.nextFloat() * 0.3f);
    }

    private boolean onCooldown(String cmd, String user) {
        long now = System.currentTimeMillis();
        Long a = lastByCmd.get(cmd);
        if (a != null && now - a < cfg.cooldowns.getOrDefault(cmd, 10) * 1000L) return true;
        Long b = lastByUser.get(user.toLowerCase(Locale.ROOT));
        return b != null && now - b < cfg.userCooldownSec * 1000L;
    }

    /** Point d'entrée UNIQUE des événements (Twitch ou /box test). Toujours appelé sur le thread serveur. */
    public String dispatch(String user, String cmd, boolean test) {
        if (!valid || cfg == null) return test ? "Box non définie." : null;
        ServerWorld w = world();
        if (w == null) return test ? "Dimension introuvable." : null;

        if (cmd.equals("progression") || cmd.equals("top")) {
            long now = System.currentTimeMillis();
            if (!test && now - lastInfoMs < 15_000) return null;
            lastInfoMs = now;
            String msg = cmd.equals("top") ? topText() : progressText();
            server.getPlayerManager().broadcast(Text.literal(msg).formatted(Formatting.AQUA), false);
            log("chat", user, "!" + cmd);
            return msg;
        }
        if (!BoxConfig.EVENT_NAMES.contains(cmd)) return null;
        if (!test) {
            if (status != Status.RUNNING || !cfg.interactionsEnabled) return null;
            if (!cfg.events.getOrDefault(cmd, false)) return null;
            if ((cmd.equals("tnt")) && !cfg.tntEnabled) return null;
            if (onCooldown(cmd, user)) return null;
            Viewer v = viewers.get(user.toLowerCase(Locale.ROOT));
            if (cfg.maxEventsPerUser > 0 && v != null && v.events >= cfg.maxEventsPerUser) return null;
        }

        int n = cfg.blocksPerEvent;
        String type = "good", text;
        int placed = 0;
        switch (cmd) {
            case "aide" -> { placed = placeRandom(w, n); text = "Aide : +" + placed + " blocs"; }
            case "bloc" -> { placed = placeRandom(w, n * 2); text = "+" + placed + " blocs"; }
            case "remplir" -> { placed = fillLowest(w, n * 5); text = "Remplissage : +" + placed + " blocs"; }
            case "pluie" -> { rain(w, cfg.rainBlocks); text = "Pluie de " + cfg.rainBlocks + " blocs"; placed = cfg.rainBlocks; }
            case "tnt" -> {
                for (int i = 0; i < cfg.tntCount; i++) tnt(w, cfg.tntPower, i * 12);
                type = "tnt"; text = "TNT ×" + cfg.tntCount + " (rayon " + cfg.tntPower + ")";
            }
            case "chaos" -> {
                if (cfg.tntEnabled) {
                    for (int i = 0; i < cfg.tntCount * 3; i++) tnt(w, Math.round(cfg.tntPower * 1.5f), i * 10);
                    type = "tnt"; text = "CHAOS : " + (cfg.tntCount * 3) + " TNT puissantes + pluie";
                } else text = "CHAOS : double pluie de blocs";
                rain(w, cfg.rainBlocks * 2);
                if (!cfg.tntEnabled) placed = cfg.rainBlocks * 2;
            }
            default -> { // chance
                int r = rnd.nextInt(10);
                if (r < 4) { placed = placeRandom(w, n * 3); text = "Chance : +" + placed + " blocs !"; }
                else if (r < 6 && cfg.tntEnabled) { tnt(w, cfg.tntPower, 0); type = "tnt"; text = "Malchance : une TNT !"; }
                else if (r < 8) { rain(w, cfg.rainBlocks); placed = cfg.rainBlocks; text = "Chance : pluie de blocs"; }
                else { placed = fillLowest(w, n * 6); text = "JACKPOT : +" + placed + " blocs"; }
            }
        }

        if (!test) {
            long now = System.currentTimeMillis();
            lastByCmd.put(cmd, now);
            lastByUser.put(user.toLowerCase(Locale.ROOT), now);
        }
        Viewer v = viewers.computeIfAbsent(user.toLowerCase(Locale.ROOT) + "|" + user, k -> new Viewer());
        // la clé garde la casse d'affichage ; on fusionne les anciennes clés minuscules
        Viewer old = viewers.get(user.toLowerCase(Locale.ROOT));
        if (old != null) { v.score += old.score; v.events += old.events; viewers.remove(user.toLowerCase(Locale.ROOT)); }
        v.events++;
        v.score += placed;
        lastUser = user;
        log("chat", user, "!" + cmd);
        log(type, user, text);
        server.getPlayerManager().broadcast(Text.literal("[" + user + "] " + text).formatted(type.equals("tnt") ? Formatting.RED : Formatting.GREEN), false);
        return text;
    }

    // ---------------------------------------------------------------- textes / état

    private void log(String type, String user, String text) {
        Deque<EventLog> q = type.equals("chat") ? chat : events;
        q.addFirst(new EventLog(nextId++, user, type, text));
        while (q.size() > 12) q.removeLast();
    }

    public String progressText() {
        double pct = volume == 0 ? 0 : Math.min(100.0, filled * 100.0 / volume);
        return String.format(Locale.ROOT, "Progression : %d / %d blocs (%.1f %%), il reste %d blocs.",
                filled, volume, pct, Math.max(0, volume - filled));
    }

    private List<Map.Entry<String, Viewer>> sortedViewers() {
        List<Map.Entry<String, Viewer>> l = new ArrayList<>(viewers.entrySet());
        l.sort(Comparator.comparingLong((Map.Entry<String, Viewer> e) -> e.getValue().score).reversed());
        return l;
    }

    private static String displayName(String key) {
        int i = key.indexOf('|');
        return i >= 0 ? key.substring(i + 1) : key;
    }

    public String topText() {
        StringBuilder sb = new StringBuilder("Top viewers : ");
        int i = 0;
        for (Map.Entry<String, Viewer> e : sortedViewers()) {
            if (i++ >= 5) break;
            sb.append(i).append(". ").append(displayName(e.getKey())).append(" (").append(e.getValue().score).append(")  ");
        }
        return i == 0 ? "Top viewers : personne pour l'instant." : sb.toString();
    }

    private String buildJson() {
        JsonObject o = new JsonObject();
        o.addProperty("status", status.name());
        o.addProperty("valid", valid);
        o.addProperty("filled", Math.min(filled, volume));
        o.addProperty("volume", volume);
        o.addProperty("remaining", Math.max(0, volume - filled));
        o.addProperty("percent", volume == 0 ? 0.0 : Math.min(100.0, filled * 100.0 / volume));
        o.addProperty("lastUser", lastUser);
        o.addProperty("twitch", twitch.status);
        o.addProperty("twitchOk", twitch.connected);
        o.addProperty("overlayUrl", overlayUrl());
        JsonObject c = new JsonObject();
        c.addProperty("all", cfg.interactionsEnabled);
        c.addProperty("tnt", cfg.tntEnabled);
        c.addProperty("blocks", cfg.blocksPerEvent);
        c.addProperty("tntpower", cfg.tntPower);
        c.addProperty("tntcount", cfg.tntCount);
        c.addProperty("cdtnt", cfg.cooldowns.getOrDefault("tnt", 20));
        c.addProperty("rain", cfg.rainBlocks);
        c.addProperty("usercd", cfg.userCooldownSec);
        c.addProperty("maxuser", cfg.maxEventsPerUser);
        c.addProperty("block", cfg.fillBlocks.get(cfg.fillBlockIndex));
        c.addProperty("channel", cfg.twitchChannel);
        JsonObject ev = new JsonObject();
        for (String n : BoxConfig.EVENT_NAMES) ev.addProperty(n, cfg.events.getOrDefault(n, true));
        c.add("ev", ev);
        o.add("cfg", c);
        o.add("events", logArray(events));
        o.add("chat", logArray(chat));
        JsonArray top = new JsonArray();
        int i = 0;
        for (Map.Entry<String, Viewer> e : sortedViewers()) {
            if (i++ >= 8) break;
            JsonObject t = new JsonObject();
            t.addProperty("name", displayName(e.getKey()));
            t.addProperty("score", e.getValue().score);
            t.addProperty("events", e.getValue().events);
            top.add(t);
        }
        o.add("top", top);
        return o.toString();
    }

    private JsonArray logArray(Deque<EventLog> q) {
        JsonArray a = new JsonArray();
        for (EventLog e : q) {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.id());
            j.addProperty("user", e.user());
            j.addProperty("type", e.type());
            j.addProperty("text", e.text());
            a.add(j);
        }
        return a;
    }

    // ---------------------------------------------------------------- réseau GUI

    public void pushState(boolean open) {
        if (server == null) return;
        String json = buildJson();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (isAdminPlayer(p)) ServerPlayNetworking.send(p, new StatePayload(json, open));
        }
    }

    public void sendTo(ServerPlayerEntity p, boolean open) {
        ServerPlayNetworking.send(p, new StatePayload(buildJson(), open));
    }

    // ---------------------------------------------------------------- droits

    /**
     * Admin = console, propriétaire du monde en solo, ou joueur listé dans config.admins.
     * Les viewers Twitch n'ont AUCUN lien avec ce système : ils ne passent que par dispatch().
     */
    public boolean isAdmin(ServerCommandSource src) {
        if (src.getEntity() == null) return true; // console
        return src.getEntity() instanceof ServerPlayerEntity p && isAdminPlayer(p);
    }

    public boolean isAdminPlayer(ServerPlayerEntity p) {
        String name = p.getName().getString();
        for (String a : cfg.admins) if (a.equalsIgnoreCase(name)) return true;
        if (server != null && server.isSingleplayer()) {
            String owner = server.getSinglePlayerName();
            return owner != null && owner.equalsIgnoreCase(name);
        }
        return false;
    }

    // ---------------------------------------------------------------- réglages (menu / commandes)

    public String adjust(String key, int delta) {
        switch (key) {
            case "blocks" -> cfg.blocksPerEvent = BoxConfig.clamp(cfg.blocksPerEvent + delta * 5, 1, 1000);
            case "tntpower" -> cfg.tntPower = BoxConfig.clamp(cfg.tntPower + delta, 2, 30);
            case "tntcount" -> cfg.tntCount = BoxConfig.clamp(cfg.tntCount + delta, 1, 10);
            case "rain" -> cfg.rainBlocks = BoxConfig.clamp(cfg.rainBlocks + delta * 10, 5, 500);
            case "cdtnt" -> cfg.cooldowns.put("tnt", BoxConfig.clamp(cfg.cooldowns.getOrDefault("tnt", 20) + delta * 5, 1, 600));
            case "usercd" -> cfg.userCooldownSec = BoxConfig.clamp(cfg.userCooldownSec + delta * 5, 0, 600);
            case "maxuser" -> cfg.maxEventsPerUser = BoxConfig.clamp(cfg.maxEventsPerUser + delta, 0, 1000);
            default -> { return "Réglage inconnu : " + key; }
        }
        cfg.save();
        return "OK";
    }

    public String toggle(String key) {
        if (key.equals("all")) cfg.interactionsEnabled = !cfg.interactionsEnabled;
        else if (key.equals("tnt")) cfg.tntEnabled = !cfg.tntEnabled;
        else if (BoxConfig.EVENT_NAMES.contains(key)) cfg.events.put(key, !cfg.events.getOrDefault(key, true));
        else return "Option inconnue : " + key;
        cfg.save();
        return "OK";
    }

    public String cycleBlock(int dir) {
        cfg.fillBlockIndex = Math.floorMod(cfg.fillBlockIndex + dir, cfg.fillBlocks.size());
        cfg.save();
        return "Bloc de remplissage : " + cfg.fillBlocks.get(cfg.fillBlockIndex);
    }

    public String setBlock(String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null || Registries.BLOCK.get(i) == Blocks.AIR) return "Bloc inconnu : " + id;
        if (!cfg.fillBlocks.contains(i.toString())) cfg.fillBlocks.add(i.toString());
        cfg.fillBlockIndex = cfg.fillBlocks.indexOf(i.toString());
        cfg.save();
        return "Bloc de remplissage : " + i;
    }

    public String setChannel(String ch) {
        cfg.twitchChannel = ch.toLowerCase(Locale.ROOT).replaceFirst("^#", "");
        cfg.save();
        twitch.start(cfg.twitchChannel);
        return "Salon Twitch : " + cfg.twitchChannel;
    }

    // ---------------------------------------------------------------- persistance de l'état

    private void loadSaved() {
        try {
            if (Files.exists(STATE_FILE)) {
                Saved s = GSON.fromJson(Files.readString(STATE_FILE, StandardCharsets.UTF_8), Saved.class);
                if (s != null) {
                    status = Status.valueOf(s.status);
                    if (status == Status.RUNNING) status = Status.PAUSED; // reprise manuelle après redémarrage
                    lastUser = s.lastUser == null ? "" : s.lastUser;
                    viewers = s.viewers == null ? new LinkedHashMap<>() : s.viewers;
                }
            }
        } catch (Exception e) {
            BoxTwitchMod.LOG.warn("État précédent illisible, repartir de zéro", e);
        }
    }

    private void saveSaved() {
        try {
            Saved s = new Saved();
            s.status = status.name();
            s.lastUser = lastUser;
            s.viewers = viewers;
            Files.writeString(STATE_FILE, GSON.toJson(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BoxTwitchMod.LOG.warn("Impossible d'enregistrer l'état", e);
        }
    }
}
