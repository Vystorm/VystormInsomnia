package at.vystorm.insomnia.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /insomnia, /dream, /wake and /dreamloot through Core's command trees with the real language files. */
class InsomniaCommandsTest {
    private static final List<String> FILES = List.of("en", "de", "zh_cn");
    private final List<String> chat = new ArrayList<>();
    private final InsomniaCommand insomnia = mock(InsomniaCommand.class);
    private final DreamCommand dreams = mock(DreamCommand.class);
    private Map<String, CommandRegistration> trees;

    private static String read(String name) throws Exception {
        try (InputStream in = InsomniaCommandsTest.class.getResourceAsStream("/lang/" + name + ".yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormInsomnia");
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : FILES) files.put(f, read(f));
        Lang lang = Lang.create("VystormInsomnia", files);
        when(insomnia.execute(any(), anyString(), any(String[].class))).thenReturn(true);
        when(insomnia.effectNames()).thenReturn(List.of("whisper", "shadow"));
        when(insomnia.mobTiers()).thenReturn(List.of("1", "2"));
        trees = new LinkedHashMap<>();
        for (CommandSpec spec : InsomniaCommands.specs(insomnia, dreams)) trees.put(spec.name(), CommandTesting.detached(plugin, lang, spec));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    private Player admin(Locale locale) { return player(locale, InsomniaCommand.USE, InsomniaCommand.ADMIN, "insomnia.dream"); }

    private void run(CommandSender sender, String label, String... args) { trees.get(label).execute(sender, label, args); }

    @Test
    void givenAllCommands_whenListed_thenFourRootsWithoutConflicts() {
        assertEquals(List.of("insomnia", "dream", "wake", "dreamloot"), List.copyOf(trees.keySet()));
        for (CommandRegistration r : trees.values()) assertEquals(List.of(), r.conflicts(), r.label());
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() {
        CommandRegistration r = trees.get("insomnia");
        String[] none = {""};
        assertEquals(List.of("menu", "status", "history", "triggers", "help"), r.suggest(player(Locale.US, InsomniaCommand.USE), "insomnia", none));
        assertEquals(List.of("menü", "status", "verlauf", "auslöser", "admin", "hilfe"), r.suggest(admin(Locale.GERMANY), "schlaflos", none));
        assertEquals(List.of("菜单", "状态", "记录", "触发", "管理", "帮助"), r.suggest(admin(Locale.SIMPLIFIED_CHINESE), "失眠", none));
        assertEquals(List.of("abfragen", "müdigkeit", "schlafschuld", "ausruhen", "effekt", "mob", "neuladen", "übersicht"),
                r.suggest(admin(Locale.GERMANY), "insomnia", new String[]{"admin", ""}));
        assertEquals(List.of("whisper", "shadow"), r.suggest(admin(Locale.US), "insomnia", new String[]{"admin", "effect", "Bob", ""}));
        assertEquals(List.of("+10"), r.suggest(admin(Locale.US), "insomnia", new String[]{"admin", "setfatigue", "Bob", "+"}));
        assertEquals(List.of(), r.suggest(player(Locale.US), "insomnia", none), "without insomnia.use nothing");
    }

    @Test
    void givenOldEnglishPaths_whenRun_thenOldExecutorGetsTheSameArguments() {
        Player admin = admin(Locale.GERMANY);
        run(admin, "insomnia");
        verify(insomnia).execute(admin, "insomnia");
        for (String sub : List.of("menu", "status", "history", "triggers", "admin")) {
            run(admin, "insomnia", sub);
            verify(insomnia).execute(admin, "insomnia", sub);
        }
        run(admin, "insomnia", "MENU");
        verify(insomnia, times(2)).execute(admin, "insomnia", "menu");
        for (String sub : List.of("get", "sleep")) {
            run(admin, "insomnia", "admin", sub, "Bob");
            verify(insomnia).execute(admin, "insomnia", "admin", sub, "Bob");
            run(admin, "insomnia", "admin", sub);
            verify(insomnia).execute(admin, "insomnia", "admin", sub);
        }
        for (String[] form : List.of(new String[]{"setfatigue", "Bob", "+10"}, new String[]{"setdebt", "Bob", "50"},
                new String[]{"effect", "Bob", "whisper"}, new String[]{"effect", "Bob"}, new String[]{"spawnmob", "Bob", "2"},
                new String[]{"setfatigue", "Bob"}, new String[]{"reload"}, new String[]{"overview"})) {
            String[] args = new String[form.length + 1];
            args[0] = "admin";
            System.arraycopy(form, 0, args, 1, form.length);
            run(admin, "insomnia", args);
            verify(insomnia).execute(admin, "insomnia", args);
        }
        for (String name : List.of("dream", "wake", "dreamloot")) {
            run(admin, name);
            verify(dreams).run(admin, name);
        }
    }

    @Test
    void givenLocalizedNames_whenRun_thenEnglishIdsReachTheOldExecutor() {
        Player english = admin(Locale.US);
        trees.get("insomnia").execute(english, "schlaflos", new String[]{"verwaltung", "müdigkeit", "Bob", "-10"});
        verify(insomnia).execute(english, "schlaflos", "admin", "setfatigue", "Bob", "-10");
        trees.get("insomnia").execute(english, "失眠", new String[]{"记录"});
        verify(insomnia).execute(english, "失眠", "history");
        trees.get("wake").execute(english, "aufwachen", new String[0]);
        verify(dreams).run(english, "wake");
    }

    @Test
    void givenConsole_whenRun_thenAdminWorksAndPlayerCommandsAreRefused() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        run(console, "insomnia", "admin", "get", "Bob");
        verify(insomnia).execute(console, "insomnia", "admin", "get", "Bob");
        run(console, "insomnia", "admin", "reload");
        verify(insomnia).execute(console, "insomnia", "admin", "reload");
        run(console, "insomnia");
        verify(insomnia).execute(console, "insomnia");
        run(console, "insomnia", "status");
        run(console, "wake");
        verify(insomnia, never()).execute(console, "insomnia", "status");
        verify(dreams, never()).run(any(), anyString());
        assertEquals(List.of("Only players can do that.", "Only players can do that."), chat);
    }

    @Test
    void givenNoAdminPermission_whenAdminRun_thenRefused() {
        Player player = player(Locale.GERMANY, InsomniaCommand.USE);
        run(player, "insomnia", "admin", "reload");
        verify(insomnia, never()).execute(any(), anyString(), any(String[].class));
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() {
        trees.get("insomnia").execute(admin(Locale.GERMANY), "schlaflos", new String[]{"hilfe", "admin"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/schlaflos admin müdigkeit SPIELER 0-100|+n|-n – Die Müdigkeit eines Spielers setzen"), all);
        assertTrue(all.contains("/schlaflos admin neuladen – Konfiguration prüfen und neu laden"), all);
        chat.clear();
        trees.get("insomnia").execute(admin(Locale.GERMANY), "schlaflos", new String[]{"hilfe", "admin", "2"});
        assertTrue(String.join("\n", chat).contains("/schlaflos admin übersicht – Müdigkeits-Übersicht"), chat.toString());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndEveryNodeDescribed() throws Exception {
        Map<String, YamlConfiguration> yaml = new LinkedHashMap<>();
        for (String f : FILES) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(new StringReader(read(f)));
            yaml.put(f, y);
        }
        Set<String> english = new TreeSet<>(yaml.get("en").getConfigurationSection("commands").getKeys(true));
        for (String f : FILES) assertEquals(english, new TreeSet<>(yaml.get(f).getConfigurationSection("commands").getKeys(true)), f);
        List<CommandSpec> nodes = new ArrayList<>();
        for (CommandSpec root : InsomniaCommands.specs(null, null)) collect(root, nodes);
        for (String f : FILES) for (CommandSpec node : nodes) {
            assertTrue(yaml.get(f).isString(node.langPath() + ".description"), f + " " + node.langPath());
            assertTrue(yaml.get(f).isList(node.langPath() + ".aliases"), f + " " + node.langPath());
            if (!node.isRoot()) assertTrue(yaml.get(f).isString(node.langPath() + ".name"), f + " " + node.langPath());
        }
    }

    private static void collect(CommandSpec node, List<CommandSpec> out) {
        out.add(node);
        for (CommandSpec child : node.children().values()) collect(child, out);
    }
}
