package fr.boxtwitch;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** /box ... : toutes les sous-commandes sont réservées aux admins ; /box seul ouvre le menu. */
public final class BoxCommands {
    private interface Cmd { int run(CommandContext<ServerCommandSource> c) throws CommandSyntaxException; }
    private static final Pattern CHANNEL = Pattern.compile("[a-zA-Z0-9_]{3,25}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,25}");

    private static Command<ServerCommandSource> guard(Cmd cmd) {
        return c -> {
            if (BoxManager.I.cfg == null || !BoxManager.I.isAdmin(c.getSource())) {
                c.getSource().sendError(Text.literal("Accès réservé au propriétaire du monde / aux admins du défi."));
                return 0;
            }
            int r = cmd.run(c);
            BoxManager.I.pushState(false);
            return r;
        };
    }

    private static int say(CommandContext<ServerCommandSource> c, String msg) {
        c.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.YELLOW), false);
        return 1;
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        BoxManager m = BoxManager.I;
        d.register(literal("box")
            .executes(c -> {
                if (m.cfg == null) return 0;
                if (c.getSource().getEntity() instanceof ServerPlayerEntity p) {
                    if (m.isAdminPlayer(p)) { m.sendTo(p, true); return 1; }
                    return say(c, m.progressText());
                }
                return say(c, m.progressText());
            })
            .then(literal("status").executes(c -> say(c, m.progressText() + " Statut : " + m.status())))
            .then(literal("pos1").executes(guard(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                return say(c, m.setCorner(1, p.getEntityWorld(), p.getBlockPos()));
            })))
            .then(literal("pos2").executes(guard(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                return say(c, m.setCorner(2, p.getEntityWorld(), p.getBlockPos()));
            })))
            .then(literal("size")
                .then(argument("x", IntegerArgumentType.integer(3, 300))
                .then(argument("y", IntegerArgumentType.integer(3, 300))
                .then(argument("z", IntegerArgumentType.integer(3, 300)).executes(guard(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    return say(c, m.setSize(p.getEntityWorld(), p.getBlockPos(),
                            IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")));
                }))))))
            .then(literal("build").executes(guard(c -> say(c, m.build()))))
            .then(literal("start").executes(guard(c -> say(c, m.setStatus(BoxManager.Status.RUNNING)))))
            .then(literal("pause").executes(guard(c -> say(c, m.setStatus(BoxManager.Status.PAUSED)))))
            .then(literal("resume").executes(guard(c -> say(c, m.setStatus(BoxManager.Status.RUNNING)))))
            .then(literal("stop").executes(guard(c -> say(c, m.setStatus(BoxManager.Status.IDLE)))))
            .then(literal("reset").then(literal("confirm").executes(guard(c -> say(c, m.resetChallenge())))))
            .then(literal("clear").then(literal("confirm").executes(guard(c -> say(c, m.clearInterior())))))
            .then(literal("toggle").then(argument("key", StringArgumentType.word()).executes(guard(c ->
                say(c, m.toggle(StringArgumentType.getString(c, "key").toLowerCase(Locale.ROOT)))))))
            .then(literal("adj").then(argument("key", StringArgumentType.word())
                .then(argument("delta", IntegerArgumentType.integer(-100, 100)).executes(guard(c ->
                    say(c, m.adjust(StringArgumentType.getString(c, "key"), IntegerArgumentType.getInteger(c, "delta"))))))))
            .then(literal("block")
                .then(literal("next").executes(guard(c -> say(c, m.cycleBlock(1)))))
                .then(literal("prev").executes(guard(c -> say(c, m.cycleBlock(-1)))))
                .then(literal("set").then(argument("id", StringArgumentType.word()).executes(guard(c ->
                    say(c, m.setBlock(StringArgumentType.getString(c, "id"))))))))
            .then(literal("channel").then(argument("name", StringArgumentType.word()).executes(guard(c -> {
                String n = StringArgumentType.getString(c, "name");
                if (!CHANNEL.matcher(n.replaceFirst("^#", "")).matches()) return say(c, "Nom de salon invalide.");
                return say(c, m.setChannel(n));
            }))))
            .then(literal("twitch")
                .then(literal("reconnect").executes(guard(c -> { m.twitch.start(m.cfg.twitchChannel); return say(c, "Reconnexion…"); })))
                .then(literal("off").executes(guard(c -> { m.cfg.twitchEnabled = false; m.cfg.save(); m.twitch.stop(); return say(c, "Twitch coupé."); })))
                .then(literal("on").executes(guard(c -> { m.cfg.twitchEnabled = true; m.cfg.save(); m.twitch.start(m.cfg.twitchChannel); return say(c, "Twitch activé."); })))
                .then(literal("status").executes(guard(c -> say(c, "Twitch : " + m.twitch.status)))))
            .then(literal("overlay").executes(guard(c -> say(c, "URL de la source navigateur Streamlabs : " + m.overlayUrl()))))
            .then(literal("test").then(argument("cmd", StringArgumentType.word()).executes(guard(c -> {
                String r = m.dispatch("Testeur", StringArgumentType.getString(c, "cmd").toLowerCase(Locale.ROOT), true);
                return say(c, r == null ? "Commande inconnue." : "Test : " + r);
            })).then(argument("viewer", StringArgumentType.word()).executes(guard(c -> {
                String v = StringArgumentType.getString(c, "viewer");
                if (!NAME.matcher(v).matches()) return say(c, "Pseudo invalide.");
                String r = m.dispatch(v, StringArgumentType.getString(c, "cmd").toLowerCase(Locale.ROOT), true);
                return say(c, r == null ? "Commande inconnue." : "Test : " + r);
            }))))) 
            .then(literal("admin")
                .then(literal("add").then(argument("name", StringArgumentType.word()).executes(guard(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    if (!NAME.matcher(n).matches()) return say(c, "Pseudo invalide.");
                    if (m.cfg.admins.stream().noneMatch(a -> a.equalsIgnoreCase(n))) m.cfg.admins.add(n);
                    m.cfg.save();
                    return say(c, n + " est maintenant admin du défi.");
                }))))
                .then(literal("remove").then(argument("name", StringArgumentType.word()).executes(guard(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    m.cfg.admins.removeIf(a -> a.equalsIgnoreCase(n));
                    m.cfg.save();
                    return say(c, n + " retiré des admins.");
                }))))
                .then(literal("list").executes(guard(c -> say(c, "Admins : " + m.cfg.admins)))))
        );
    }
}
