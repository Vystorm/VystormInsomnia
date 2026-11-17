package at.vystorm.insomnia.sleep;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.chat.ChatActions;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.dream.DreamService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.SleepDebtReminderService;
import at.vystorm.insomnia.ui.Messages;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Asks a player who lies down what they want: sleep until a time (with time acceleration), enter the dream realm,
 * or get up. Native Core dialog; clickable chat for clients without dialogs (Bedrock via Floodgate) or players who
 * chose chat in {@code /vsettings}. Day naps get a short version (keep napping, dream realm, get up) only when the
 * dream realm is open to the player, since the clock never runs faster for a nap.
 */
public final class SleepDialog implements Listener {
    public static final String SECTION = "sleep";

    private final Plugin plugin;
    private final API api;
    private final InsomniaConfig config;
    private final Messages texts;
    private final SleepAccelerator accelerator;
    private final DreamService dreams;
    private final FatigueService fatigue;

    public SleepDialog(Plugin plugin, API api, InsomniaConfig config, Messages texts, SleepAccelerator accelerator,
                       DreamService dreams, FatigueService fatigue) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.texts = texts;
        this.accelerator = accelerator;
        this.dreams = dreams;
        this.fatigue = fatigue;
    }

    /** Settings key of the personal section ({@code /vsettings}). */
    public static String settingsKey(Plugin plugin) { return plugin.getName().toLowerCase(Locale.ROOT) + ":" + SECTION; }

    /** Personal settings in {@code /vsettings}: how to ask, the saved choice and the own wake-up hour. */
    public void registerSettings() {
        LinkedHashMap<String, String> modes = new LinkedHashMap<>();
        for (SleepPreferences.Mode mode : SleepPreferences.Mode.values())
            modes.put(mode.id(), texts.ref("sleep_choice.settings.mode_" + mode.id()));
        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        for (String choice : SleepPreferences.CHOICES) choices.put(choice, texts.ref("sleep_choice.settings.choice_" + choice));
        api.settings().register(plugin, SettingsSection.player(SECTION, texts.ref("sleep_choice.settings.title"),
                texts.ref("sleep_choice.settings.description"), List.of(
                        api.settings().prefs().choice(plugin, SleepPreferences.MODE, texts.ref("sleep_choice.settings.mode"),
                                texts.ref("sleep_choice.settings.mode_desc"), modes, SleepPreferences.Mode.ASK.id()),
                        api.settings().prefs().choice(plugin, SleepPreferences.CHOICE, texts.ref("sleep_choice.settings.choice"),
                                texts.ref("sleep_choice.settings.choice_desc"), choices, SleepPreferences.DEFAULT_CHOICE),
                        api.settings().prefs().number(plugin, SleepPreferences.CUSTOM_HOUR, texts.ref("sleep_choice.settings.hour"),
                                texts.ref("sleep_choice.settings.hour_desc"), 0, 23, 1, SleepPreferences.DEFAULT_CUSTOM_HOUR),
                        api.settings().prefs().toggle(plugin, SleepDebtReminderService.PREFERENCE,
                                texts.ref("debt_reminder.preference"), texts.ref("debt_reminder.preference_desc"), true))));
    }

    SleepPreferences prefs(Player player) {
        return new SleepPreferences(new SleepPreferences.Store() {
            @Override public String text(String id, String fallback) { return api.settings().prefs().text(plugin, player, id, fallback); }
            @Override public void setText(String id, String value) { api.settings().prefs().setText(plugin, player, id, value); }
            @Override public double number(String id, double fallback) { return api.settings().prefs().number(plugin, player, id, fallback); }
            @Override public void setNumber(String id, double value) { api.settings().prefs().setNumber(plugin, player, id, value); }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        boolean night = event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK;
        boolean maybeNap = event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW;
        if (!night && !maybeNap) return;
        UUID id = event.getPlayer().getUniqueId();
        // One tick later the player really lies in bed and Insomnia's nap handling has decided.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isSleeping()) return;
            if (dreams != null && dreams.napping(id)) nap(player);
            else if (night) night(player);
        });
    }

    /** Night sleep: the saved choice counts at once (time already speeds up), then the player is asked. */
    void night(Player player) {
        SleepPreferences prefs = prefs(player);
        SleepPreferences.Choice saved = prefs.choice();
        boolean faster = accelerator.active(player.getWorld());
        if (faster) accelerator.sleepUntil(player, saved.dream() ? 0 : clockOf(saved));
        if (!config.sleepDialogEnabled()) return;
        switch (prefs.mode()) {
            case AUTO -> {
                if (saved.dream() && dreamReady(player)) { dreams.enter(player); return; }
                api.compat().platform().sendMessage(player, texts.chat(player, "sleep_choice.auto", "time", wakeClock(player, faster)));
            }
            case CHAT -> chat(player, false);
            case ASK -> {
                if (DialogSupport.supported(api.compat().server(), player)) dialog(player); else chat(player, false);
            }
        }
    }

    /** Daytime nap: only worth asking while the dream realm is open to the player. */
    void nap(Player player) {
        if (!config.sleepDialogEnabled() || !config.sleepDialogDayNaps() || !dreamReady(player)) return;
        SleepPreferences prefs = prefs(player);
        switch (prefs.mode()) {
            case AUTO -> { if (prefs.choice().dream()) dreams.enter(player); }
            case CHAT -> chat(player, true);
            case ASK -> { if (DialogSupport.supported(api.compat().server(), player)) napDialog(player); else chat(player, true); }
        }
    }

    private String wakeClock(Player player, boolean faster) {
        OptionalLong target = faster ? accelerator.target(player.getUniqueId()) : OptionalLong.empty();
        return WakeTimes.clock(target.isPresent() ? target.getAsLong() : 0L);
    }

    private int clockOf(SleepPreferences.Choice choice) {
        if (choice.custom()) return WakeTimes.clockTicks(choice.customHour(), 0);
        int preset = config.sleepPreset(choice.id());
        return Math.max(0, preset);
    }

    private boolean dreamReady(Player player) {
        return dreams != null && dreams.enabled() && player.hasPermission("insomnia.dream")
                && player.getGameMode() == GameMode.SURVIVAL && fatigue.ready(player.getUniqueId())
                && fatigue.fatigue(player.getUniqueId()) >= config.dreamMinimumFatigue();
    }

    private Component line(Player player, String key, Object... placeholders) { return texts.component(player, key, placeholders); }

    private List<String> visiblePresets() {
        List<String> visible = new ArrayList<>();
        for (String preset : SleepPreferences.PRESETS) if (config.sleepPreset(preset) >= 0) visible.add(preset);
        return visible;
    }

    private void dialog(Player player) {
        boolean faster = accelerator.active(player.getWorld());
        SleepPreferences prefs = prefs(player);
        List<Component> body = new ArrayList<>();
        body.add(line(player, "sleep_choice.now", "time", WakeTimes.clock(player.getWorld().getFullTime())));
        body.add(line(player, faster ? "sleep_choice.faster" : "sleep_choice.no_acceleration"));
        dreamLine(player).ifPresent(body::add);

        List<DialogInput> inputs = new ArrayList<>();
        List<DialogButton> buttons = new ArrayList<>();
        if (faster) {
            for (String preset : visiblePresets()) {
                SleepPreferences.Choice choice = new SleepPreferences.Choice(preset, prefs.customHour());
                buttons.add(new DialogButton(line(player, "sleep_choice.button." + preset, "time", WakeTimes.clock(config.sleepPreset(preset))),
                        (p, response) -> choose(p, choice, remember(response))));
            }
            if (config.sleepDialogCustomTime()) {
                inputs.add(hourPicker(player, prefs.customHour()));
                buttons.add(new DialogButton(line(player, "sleep_choice.button.custom"),
                        (p, response) -> choose(p, new SleepPreferences.Choice(SleepPreferences.CUSTOM, hour(response, prefs.customHour())), remember(response))));
            }
        } else {
            buttons.add(new DialogButton(line(player, "sleep_choice.button.sleep"),
                    (p, response) -> choose(p, new SleepPreferences.Choice(SleepPreferences.DEFAULT_CHOICE, prefs.customHour()), remember(response))));
        }
        if (dreamReady(player)) buttons.add(new DialogButton(line(player, "sleep_choice.button.dream"),
                (p, response) -> choose(p, new SleepPreferences.Choice(SleepPreferences.DREAM, prefs.customHour()), remember(response))));
        buttons.add(new DialogButton(line(player, "sleep_choice.button.get_up"), (p, response) -> getUp(p)));
        inputs.add(DialogInput.bool("remember", line(player, "sleep_choice.remember")).initial(false).build());

        api.dialogs().open(plugin, player, new DialogRequest(line(player, "sleep_choice.title"),
                body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(), inputs, buttons,
                p -> p.isOnline() && p.isSleeping(), () -> {}, texts.text(player, "sleep_choice.keep_sleeping"), 2));
    }

    private void napDialog(Player player) {
        List<Component> body = List.of(line(player, "sleep_choice.nap_now", "time", WakeTimes.clock(player.getWorld().getFullTime())));
        SleepPreferences prefs = prefs(player);
        List<DialogButton> buttons = List.of(
                new DialogButton(line(player, "sleep_choice.button.dream"),
                        (p, response) -> choose(p, new SleepPreferences.Choice(SleepPreferences.DREAM, prefs.customHour()), remember(response))),
                new DialogButton(line(player, "sleep_choice.button.get_up"), (p, response) -> getUp(p)));
        api.dialogs().open(plugin, player, new DialogRequest(line(player, "sleep_choice.nap_title"),
                body.stream().map(c -> (DialogBody) DialogBody.plainMessage(c, 300)).toList(),
                List.of(DialogInput.bool("remember", line(player, "sleep_choice.remember")).initial(false).build()), buttons,
                p -> p.isOnline() && p.isSleeping(), () -> {}, texts.text(player, "sleep_choice.keep_napping"), 2));
    }

    private java.util.Optional<Component> dreamLine(Player player) {
        if (dreams == null || !dreams.enabled() || !player.hasPermission("insomnia.dream") || !fatigue.ready(player.getUniqueId()))
            return java.util.Optional.empty();
        long current = Math.round(fatigue.fatigue(player.getUniqueId()));
        if (dreamReady(player)) return java.util.Optional.of(line(player, "sleep_choice.dream_ready", "fatigue", current));
        return java.util.Optional.of(line(player, "sleep_choice.dream_not_tired", "min", Math.round(config.dreamMinimumFatigue()), "fatigue", current));
    }

    /** Hour picker 06:00, 07:00 ... 05:00 (the coming day). */
    private DialogInput hourPicker(Player player, int selected) {
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int hour = (6 + i) % 24;
            entries.add(SingleOptionDialogInput.OptionEntry.create(Integer.toString(hour),
                    Component.text(String.format(Locale.ROOT, "%02d:00", hour)), hour == selected));
        }
        return DialogInput.singleOption("hour", line(player, "sleep_choice.hour_label"), entries).build();
    }

    private static int hour(DialogResponseView response, int fallback) {
        String value = response == null ? null : response.getText("hour");
        if (value == null) return fallback;
        try { return Math.max(0, Math.min(23, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static boolean remember(DialogResponseView response) {
        Boolean value = response == null ? null : response.getBoolean("remember");
        return Boolean.TRUE.equals(value);
    }

    /** Applies a choice from the dialog or chat. */
    void choose(Player player, SleepPreferences.Choice choice, boolean remember) {
        prefs(player).remember(choice, remember);
        if (remember) api.compat().platform().sendMessage(player, texts.chat(player, "sleep_choice.remembered"));
        if (choice.dream()) { dreams.enter(player); return; }
        if (!player.isSleeping() || dreams != null && dreams.napping(player.getUniqueId())) return;
        OptionalLong target = accelerator.sleepUntil(player, clockOf(choice));
        if (target.isPresent()) api.compat().platform().sendMessage(player, texts.chat(player, "sleep_choice.sleeping_until", "time", WakeTimes.clock(target.getAsLong())));
        else api.compat().platform().sendMessage(player, texts.chat(player, "sleep_choice.sleeping"));
    }

    private void getUp(Player player) {
        accelerator.forget(player.getUniqueId());
        if (player.isSleeping()) player.wakeup(false);
    }

    /** Clickable chat version of the dialog. */
    private void chat(Player player, boolean napping) {
        String hover = texts.text(player, "sleep_choice.chat_hover");
        Component message = api.message().chat().text(texts.chat(player, napping ? "sleep_choice.chat_nap" : "sleep_choice.chat_night",
                "time", WakeTimes.clock(player.getWorld().getFullTime())));
        int hour = prefs(player).customHour();
        if (!napping) {
            if (accelerator.active(player.getWorld())) {
                for (String preset : visiblePresets()) {
                    SleepPreferences.Choice choice = new SleepPreferences.Choice(preset, hour);
                    message = message.append(Component.space()).append(api.message().chat().button(
                            texts.text(player, "sleep_choice.chat_button", "label", texts.plain(player, "sleep_choice.button." + preset,
                                    "time", WakeTimes.clock(config.sleepPreset(preset)))),
                            hover, p -> choose(p, choice, false)));
                }
            } else {
                message = message.append(Component.space()).append(api.message().chat().button(
                        texts.text(player, "sleep_choice.chat_button", "label", texts.plain(player, "sleep_choice.button.sleep")),
                        hover, p -> choose(p, new SleepPreferences.Choice(SleepPreferences.DEFAULT_CHOICE, hour), false)));
            }
        }
        if (dreamReady(player)) message = message.append(Component.space()).append(api.message().chat().button(
                texts.text(player, "sleep_choice.chat_button", "label", texts.plain(player, "sleep_choice.button.dream")),
                hover, p -> choose(p, new SleepPreferences.Choice(SleepPreferences.DREAM, hour), false)));
        message = message.append(Component.space()).append(api.message().chat().button(
                texts.text(player, "sleep_choice.chat_button", "label", texts.plain(player, "sleep_choice.button.get_up")),
                hover, this::getUp));
        message = message.append(Component.space()).append(api.message().chat().settings(
                texts.text(player, "sleep_choice.chat_settings"), settingsKey(plugin)));
        api.compat().platform().sendMessage(player, message);
    }
}
