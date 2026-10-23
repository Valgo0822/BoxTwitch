package fr.boxtwitch.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Menu admin : 3 pages (Défi / Événements / Twitch). Chaque bouton envoie une commande /box au serveur. */
public class BoxScreen extends Screen {
    private int page = 0;
    private boolean confirmReset = false;
    private TextFieldWidget channelField;
    private final List<ButtonWidget> dynButtons = new ArrayList<>();
    private final List<Supplier<Text>> dynLabels = new ArrayList<>();
    private final List<int[]> adjRows = new ArrayList<>();       // x, y
    private final List<String[]> adjInfo = new ArrayList<>();    // clé, libellé

    public BoxScreen() { super(Text.literal("BoxTwitch")); }

    private static JsonObject cfg() {
        JsonObject s = BoxTwitchClient.state;
        return s.has("cfg") ? s.getAsJsonObject("cfg") : new JsonObject();
    }
    private static long num(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsLong() : 0; }
    private static boolean flag(JsonObject o, String k) { return o.has(k) && o.get(k).getAsBoolean(); }
    private static String str(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsString() : ""; }

    private void cmd(String c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.networkHandler.sendChatCommand(c);
    }

    private ButtonWidget btn(int x, int y, int w, Supplier<Text> label, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(label.get(), bb -> action.run()).dimensions(x, y, w, 20).build();
        addDrawableChild(b);
        dynButtons.add(b);
        dynLabels.add(label);
        return b;
    }

    private static Text onOff(String name, boolean v) { return Text.literal(name + " : " + (v ? "ON" : "OFF")); }

    @Override
    protected void init() {
        dynButtons.clear(); dynLabels.clear(); adjRows.clear(); adjInfo.clear();
        int cx = width / 2, left = cx - 150;
        String[] tabs = {"Défi", "Événements", "Twitch"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            btn(left + i * 102, 22, 98, () -> Text.literal((page == p ? "› " : "") + tabs[p]), () -> { page = p; clearAndInit(); });
        }
        int y = 100;
        if (page == 0) {
            btn(left, y, 96, () -> Text.literal("Démarrer"), () -> cmd("box start"));
            btn(left + 102, y, 96, () -> Text.literal("Pause"), () -> cmd("box pause"));
            btn(left + 204, y, 96, () -> Text.literal("Reprendre"), () -> cmd("box resume"));
            y += 26;
            btn(left, y, 300, () -> onOff("Toutes les interactions", flag(cfg(), "all")), () -> cmd("box toggle all"));
            y += 26;
            btn(left, y, 96, () -> Text.literal("Coin 1 ici"), () -> cmd("box pos1"));
            btn(left + 102, y, 96, () -> Text.literal("Coin 2 ici"), () -> cmd("box pos2"));
            btn(left + 204, y, 96, () -> Text.literal("Construire"), () -> cmd("box build"));
            y += 26;
            btn(left, y, 300, () -> Text.literal(confirmReset ? "⚠ Confirmer la réinitialisation ?" : "Réinitialiser le défi"), () -> {
                if (confirmReset) { cmd("box reset confirm"); confirmReset = false; } else confirmReset = true;
            });
            btn(left, y + 24, 300, () -> Text.literal(confirmReset ? "Annuler" : "(scores et compteurs remis à zéro, blocs gardés)"), () -> confirmReset = false);
        } else if (page == 1) {
            btn(left, y - 24, 146, () -> onOff("TNT", flag(cfg(), "tnt")), () -> cmd("box toggle tnt"));
            btn(left + 154, y - 24, 146, () -> Text.literal("Bloc : " + str(cfg(), "block").replace("minecraft:", "") + " ▶"), () -> cmd("box block next"));
            String[][] rows = {
                {"blocks", "Blocs par événement"}, {"tntpower", "Puissance TNT (rayon)"}, {"tntcount", "TNT par événement"},
                {"cdtnt", "Fréquence TNT (recharge, s)"}, {"rain", "Blocs par pluie"}, {"usercd", "Recharge par viewer (s)"}, {"maxuser", "Max / viewer (0 = ∞)"}};
            for (String[] r : rows) {
                adjRows.add(new int[]{left, y}); adjInfo.add(r);
                final String k = r[0];
                btn(left + 232, y, 32, () -> Text.literal("−"), () -> cmd("box adj " + k + " -1"));
                btn(left + 268, y, 32, () -> Text.literal("+"), () -> cmd("box adj " + k + " 1"));
                y += 22;
            }
            y += 4;
            String[] ev = {"aide", "bloc", "remplir", "tnt", "pluie", "chance", "chaos"};
            for (int i = 0; i < ev.length; i++) {
                final String e = ev[i];
                btn(left + (i % 4) * 76, y + (i / 4) * 22, 72, () -> {
                    JsonObject o = cfg().has("ev") ? cfg().getAsJsonObject("ev") : new JsonObject();
                    return Text.literal((flag(o, e) ? "✔ " : "✘ ") + "!" + e);
                }, () -> cmd("box toggle " + e));
            }
        } else {
            channelField = new TextFieldWidget(textRenderer, left, y, 210, 20, Text.literal("Salon"));
            channelField.setMaxLength(25);
            channelField.setText(str(cfg(), "channel"));
            addDrawableChild(channelField);
            btn(left + 216, y, 84, () -> Text.literal("Appliquer"), () -> {
                String v = channelField.getText().trim();
                if (!v.isEmpty()) cmd("box channel " + v);
            });
            y += 26;
            btn(left, y, 146, () -> Text.literal("Reconnecter"), () -> cmd("box twitch reconnect"));
            btn(left + 154, y, 146, () -> Text.literal("Copier l'URL overlay"), () ->
                MinecraftClient.getInstance().keyboard.setClipboard(str(BoxTwitchClient.state, "overlayUrl")));
            y += 26;
            btn(left, y, 146, () -> Text.literal("Test !bloc"), () -> cmd("box test bloc"));
            btn(left + 154, y, 146, () -> Text.literal("Test !tnt"), () -> cmd("box test tnt"));
        }
    }

    @Override
    public boolean shouldPause() { return false; } // le serveur solo continue de tourner

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        for (int i = 0; i < dynButtons.size(); i++) dynButtons.get(i).setMessage(dynLabels.get(i).get());
        super.render(g, mouseX, mouseY, delta);
        JsonObject s = BoxTwitchClient.state;
        int cx = width / 2, left = cx - 150;
        String title = "BoxTwitch — " + str(s, "status");
        g.drawTextWithShadow(textRenderer, Text.literal(title), cx - textRenderer.getWidth(title) / 2, 8, 0xFFFFFFFF);

        // barre de progression (toujours visible)
        double pct = s.has("percent") ? s.get("percent").getAsDouble() : 0;
        g.fill(left, 50, left + 300, 62, 0xFF1B1D21);
        g.fill(left, 50, left + (int) (300 * pct / 100.0), 62, 0xFFF2B01E);
        String line = String.format(java.util.Locale.ROOT, "%.1f %%  —  %d / %d  (reste %d)", pct, num(s, "filled"), num(s, "volume"), num(s, "remaining"));
        g.drawTextWithShadow(textRenderer, Text.literal(line), left, 66, 0xFFFFFFFF);
        String last = "Dernier viewer : " + (str(s, "lastUser").isEmpty() ? "—" : str(s, "lastUser"));
        g.drawTextWithShadow(textRenderer, Text.literal(last), left, 78, 0xFFB0B0B0);
        if (!flag(s, "valid")) g.drawTextWithShadow(textRenderer, Text.literal("Box non définie : coin 1, coin 2, puis Construire"), left, 90, 0xFFFF6666);

        if (page == 1) {
            JsonObject c = cfg();
            for (int i = 0; i < adjRows.size(); i++) {
                int[] p = adjRows.get(i);
                String[] inf = adjInfo.get(i);
                g.drawTextWithShadow(textRenderer, Text.literal(inf[1] + " : " + num(c, inf[0])), p[0], p[1] + 6, 0xFFFFFFFF);
            }
        } else if (page == 2) {
            int y = 175;
            g.drawTextWithShadow(textRenderer, Text.literal("Twitch : " + str(s, "twitch")), left, y, flag(s, "twitchOk") ? 0xFF66FF99 : 0xFFFFCC66);
            g.drawTextWithShadow(textRenderer, Text.literal("Overlay (Streamlabs → Source navigateur) :"), left, y + 14, 0xFFB0B0B0);
            String url = str(s, "overlayUrl");
            g.drawTextWithShadow(textRenderer, Text.literal(url.length() > 52 ? url.substring(0, 52) + "…" : url), left, y + 26, 0xFFFFFFFF);
        }
    }
}
