package at.vystorm.insomnia.command;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import at.vystorm.insomnia.config.InsomniaConfig;

/**
 * {@code /insomnia}, {@code /dream}, {@code /wake} and {@code /dreamloot} as Vystorm Core command trees (Core 0.45.0+):
 * names, root aliases, usage and help come from {@code commands.<command>.*} in the language files
 * ({@code /schlaflos verlauf}, {@code /失眠 历史}, {@code /aufwachen}); English always works. The handlers pass the
 * English ids to {@link InsomniaCommand#execute} and {@link DreamCommand#run}, so every check and message stays the same.
 */
public final class InsomniaCommands {
    private InsomniaCommands() {}

    /** The four trees; the arguments may be {@code null} (or mocks) in tests. */
    public static List<CommandSpec> specs(InsomniaCommand c, DreamCommand d) { return specs(c, d, null); }

    /**
     * The four trees; subcommands of features switched off in {@code config} are left out of tab completion and help
     * (0.31.0). Core asks {@code config} at every completion and {@code config.reload()} updates it in place, so a
     * switch applies at once; the commands themselves answer the shared off message. {@code config} null = everything
     * shown.
     */
    public static List<CommandSpec> specs(InsomniaCommand c, DreamCommand d, InsomniaConfig config) {
        return List.of(
                CommandSpec.command("insomnia").permission(InsomniaCommand.USE)
                        .executes(ctx -> run(ctx, c))
                        .sub("menu", s -> hide(s, config, InsomniaConfig::menuEnabled).executes(ctx -> run(ctx, c, "menu")))
                        .sub("status", s -> hide(s, config, InsomniaConfig::menuEnabled).playerOnly().executes(ctx -> run(ctx, c, "status")))
                        .sub("history", s -> hide(s, config, InsomniaConfig::menuEnabled).playerOnly().arg(Arg.word("page").optional().suggests(List.of("1", "2", "3")))
                                .executes(ctx -> run(ctx, c, "history", ctx.get("page"))))
                        .sub("triggers", s -> hide(s, config, InsomniaConfig::menuEnabled).playerOnly().arg(Arg.word("page").optional().suggests(List.of("1", "2", "3")))
                                .executes(ctx -> run(ctx, c, "triggers", ctx.get("page"))))
                        .sub("admin", s -> s.permission(InsomniaCommand.ADMIN)
                                .executes(ctx -> run(ctx, c, "admin"))
                                .sub("get", a -> target(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "get"))
                                .sub("setfatigue", a -> valued(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "setfatigue", (ctx, prefix) -> List.of("0", "25", "50", "75", "100", "+10", "-10")))
                                .sub("setdebt", a -> valued(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "setdebt", (ctx, prefix) -> List.of("0", "25", "50", "75", "100", "+10", "-10")))
                                .sub("sleep", a -> target(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "sleep"))
                                .sub("effect", a -> valued(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "effect", (ctx, prefix) -> c.effectNames()))
                                .sub("spawnmob", a -> valued(hide(a, config, InsomniaConfig::adminToolsEnabled), c, "spawnmob", (ctx, prefix) -> c.mobTiers()))
                                .sub("reload", a -> a.executes(ctx -> run(ctx, c, "admin", "reload")))
                                .sub("overview", a -> hide(a, config, InsomniaConfig::adminToolsEnabled).executes(ctx -> run(ctx, c, "admin", "overview"))))
                        .build(),
                dream("dream", d, config),
                dream("wake", d, config),
                dream("dreamloot", d, config));
    }

    /**
     * Lists a node only while its switch is on, read from the live {@code config} at every completion (Core 0.65.0);
     * without a config (tests) the node is unchanged.
     */
    private static CommandSpec.Builder hide(CommandSpec.Builder sub, InsomniaConfig config, Predicate<InsomniaConfig> on) {
        return config == null ? sub : sub.visibleWhen(() -> on.test(config));
    }

    /** The switches that decide what the trees list; a reload that changes them refreshes the players' command lists. */
    public static List<Boolean> visibility(InsomniaConfig config) {
        return List.of(config.menuEnabled(), config.adminToolsEnabled(), config.dreamEnabled(), config.dreamQuestsEnabled(),
                config.dreamCompanionsEnabled(), config.dreamWeeklyEnabled());
    }

    private static CommandSpec dream(String name, DreamCommand d, InsomniaConfig config) {
        var command=hide(
CommandSpec.command(name), config, InsomniaConfig::dreamEnabled).permission("insomnia.dream").playerOnly()
                .executes(ctx -> d.run(ctx.player(), name));
        if(name.equals("dream"))command.sub("quest", s -> hide(s, config, InsomniaConfig::dreamQuestsEnabled).permission("insomnia.dream.quest")
                .arg(Arg.word("action").optional().suggests((ctx,prefix) -> List.of("status","start","pause","mercy","courage","restore","master").stream().map(ctx::valueName).toList()))
                .executes(ctx -> {String raw=ctx.get("action");String action=raw==null?null:ctx.keyword(raw,"status","start","pause","mercy","courage","restore","master");d.quest(ctx.player(),action==null?raw:action);}));
        if(name.equals("dream"))command.sub("invite", s -> hide(s, config, InsomniaConfig::dreamCompanionsEnabled).arg(Arg.playerName("player")).executes(ctx -> d.invite(ctx.player(), ctx.get("player"))))
                .sub("join", s -> hide(s, config, InsomniaConfig::dreamCompanionsEnabled).arg(Arg.playerName("player").optional()).executes(ctx -> d.join(ctx.player(), ctx.get("player"))))
                .sub("leave", s -> hide(s, config, InsomniaConfig::dreamCompanionsEnabled).executes(ctx -> d.leave(ctx.player())))
                .sub("weekly", s -> hide(s, config, InsomniaConfig::dreamWeeklyEnabled).executes(ctx -> d.weekly(ctx.player())));
        return command.build();
    }

    /** {@code admin <sub> [player]}: the player stays optional so the old per-command usage line answers a missing one. */
    private static void target(CommandSpec.Builder a, InsomniaCommand c, String sub) {
        a.arg(Arg.playerName("player").optional())
                .executes(ctx -> run(ctx, c, "admin", sub, ctx.get("player")));
    }

    private static void valued(CommandSpec.Builder a, InsomniaCommand c, String sub, Vystorm.vystorm_core.commands.Suggester values) {
        a.arg(Arg.playerName("player").optional())
                .arg(Arg.word("value").optional().suggests(values))
                .executes(ctx -> run(ctx, c, "admin", sub, ctx.get("player"), ctx.get("value")));
    }

    /** Old executor with English ids; trailing {@code null}s (left-out optional arguments) are dropped. */
    private static void run(CommandContext ctx, InsomniaCommand c, String... parts) {
        List<String> args = new ArrayList<>();
        for (String part : parts) if (part != null) args.add(part);
        if (!c.execute(ctx.sender(), ctx.label(), args.toArray(String[]::new))) ctx.sender().sendMessage(ctx.usage());
    }
}
