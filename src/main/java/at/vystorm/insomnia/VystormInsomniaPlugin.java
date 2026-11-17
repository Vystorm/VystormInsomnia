package at.vystorm.insomnia;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import at.vystorm.insomnia.api.InsomniaApi;
import at.vystorm.insomnia.command.InsomniaCommand;
import at.vystorm.insomnia.command.DreamCommand;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.core.TickGuard;
import at.vystorm.insomnia.dream.DreamService;
import at.vystorm.insomnia.integration.BloodMoonIntegration;
import at.vystorm.insomnia.integration.CityClaimIntegration;
import at.vystorm.insomnia.listener.GameplayListener;
import at.vystorm.insomnia.service.ActivityService;
import at.vystorm.insomnia.service.CampfireRestService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.HallucinationService;
import at.vystorm.insomnia.service.InsomniaMobService;
import at.vystorm.insomnia.service.MicrosleepService;
import at.vystorm.insomnia.service.PersistenceService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepQualityService;
import at.vystorm.insomnia.service.SleepService;
import at.vystorm.insomnia.service.SleepDebtReminderService;
import at.vystorm.insomnia.service.StimulantService;
import at.vystorm.insomnia.service.VisibilityLocks;
import at.vystorm.insomnia.storage.PlayerFileStore;
import at.vystorm.insomnia.storage.StorageSettings;
import at.vystorm.insomnia.ui.FatigueHud;
import at.vystorm.insomnia.ui.FatigueOverview;
import at.vystorm.insomnia.ui.FatiguePanel;
import at.vystorm.insomnia.ui.InsomniaMenu;
import at.vystorm.insomnia.ui.InsomniaWeb;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import Vystorm.vystorm_core.schedule.TaskGroup;

import java.util.List;

public final class VystormInsomniaPlugin extends JavaPlugin {
    /** Oldest supported Core: the first one that offers every service through its managers. */
    static final String MIN_CORE_VERSION = "0.64.0";

    /** The Core hub; set first thing in onEnable, right after the version gate. */
    private API api;
    private CoreBridge core;
    private InsomniaConfig config;
    private PersistenceService persistence;
    private ActivityService activity;
    private FatigueService fatigue;
    private SleepService sleep;
    private StimulantService stimulants;
    private HallucinationService hallucinations;
    private MicrosleepService microsleep;
    private InsomniaMobService mobs;
    private RestedBonusService rested;
    private CampfireRestService campfire;
    private SleepDebtReminderService debtReminders;
    private CityClaimIntegration city;
    private BloodMoonIntegration bloodMoon;
    /** The single master runtime timer (AGENTS.md); closed on disable. */
    private TaskGroup runtime;
    private long nextDirtyFlushAt;
    private TickGuard tickGuard;
    private VisibilityLocks visibility;
    private Messages messages;
    /** Client-mod HUD (fatigue bars, stage toasts). */
    private FatigueHud hud;
    /** Web page in Core's web UI; null when it could not be registered. */
    private InsomniaWeb web;
    private DreamService dreams;
    /** Time acceleration instead of the vanilla night skip; restores the gamerule on disable. */
    private at.vystorm.insomnia.sleep.SleepAccelerator accelerator;

    @Override
    public void onEnable() {
        API hub = requiredCoreApiAvailable() ? CoreApi.api().orElse(null) : null;
        if (hub == null) {
            DebugMessages.high("[VystormInsomnia/VystormInsomniaPlugin] Vystorm Core " + safeCoreVersion()
                    + " is unavailable or too old; VystormInsomnia needs Core " + MIN_CORE_VERSION + " or newer and will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = hub;
        api.admin().updates().spigot(this, 140006);

        try {
            runtime = api.tasks().of(this, "runtime");
            core = new CoreBridge(api);
            if (!api.compat().server().hasDialogs())
                getLogger().info("Native dialogs unavailable; Insomnia menus and sleep choices use clickable chat.");
            tickGuard = new TickGuard(core::high, 60_000L);
            saveDefaultConfig();
            messages = new Messages(api.lang().register(this));
            config = new InsomniaConfig(this);
            config.bindMessages(messages);

            persistence = createPersistence(api);
            if (persistence == null) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            persistence.loadStorage();
            activity = new ActivityService(config);
            fatigue = new FatigueService(config, persistence, activity);

            city = CityClaimIntegration.detect(this, core, config);
            bloodMoon = BloodMoonIntegration.detect(this, core, config);
            SleepQualityService sleepQuality = new SleepQualityService(config, city, bloodMoon);

            rested = new RestedBonusService(config, fatigue, activity);
            mobs = new InsomniaMobService(this, config, core, fatigue, bloodMoon, city);
            stimulants = new StimulantService(config, core, fatigue);
            sleep = new SleepService(config, fatigue, persistence, sleepQuality, rested, stimulants, mobs);
            fatigue.bindSleepService(sleep);
            dreams = new DreamService(this, api, config, fatigue, activity, sleep);
            if (dreams.enable()) core.mid("Dream Realm enabled with Core-managed durable visits and rewards");
            visibility = new VisibilityLocks(this);
            hallucinations = new HallucinationService(this, config, core, fatigue, sleep, visibility);
            microsleep = new MicrosleepService(this, config, core, fatigue, sleep, activity, visibility);
            campfire = new CampfireRestService(config, fatigue, sleep, activity, api.compat().platform());
            campfire.busyWhen(microsleep::isActive);
            debtReminders = new SleepDebtReminderService(config, fatigue,
                    player -> api.settings().prefs().bool(this, player, SleepDebtReminderService.PREFERENCE, true),
                    id -> dreams.inDream(id) || dreams.napping(id) || microsleep.isActive(id));

            mobs.cleanupOrphans();
            for (Player player : getServer().getOnlinePlayers()) {
                microsleep.restoreAfterCrash(player);
                fatigue.load(player, System.currentTimeMillis());
                activity.join(player);
            }

            GameplayListener gameplay = new GameplayListener(
                    this, api, config, fatigue, activity, sleep, stimulants, hallucinations, microsleep, mobs, rested, visibility);
            getServer().getPluginManager().registerEvents(gameplay, this);
            gameplay.handleBlockEvents(api.priority().stages());
            gameplay.handleSharedEvents(api.priority().stages());
            getServer().getPluginManager().registerEvents(dreams, this);
            dreams.handleSharedEvents(api.priority().stages());
            accelerator = new at.vystorm.insomnia.sleep.SleepAccelerator(this, api, config, messages);
            accelerator.apply();
            getServer().getPluginManager().registerEvents(accelerator, this);
            at.vystorm.insomnia.sleep.SleepDialog sleepDialog = new at.vystorm.insomnia.sleep.SleepDialog(
                    this, api, config, messages, accelerator, dreams, fatigue);
            getServer().getPluginManager().registerEvents(sleepDialog, this);
            hud = new FatigueHud(this, api.web().clientUi(), messages, fatigue, config::restedDurationMillis);
            getServer().getPluginManager().registerEvents(hud, this);
            gameplay.stageToasts(hud::stageChanged);

            InsomniaMenu menu = new InsomniaMenu(this, api, messages, fatigue, sleep, rested, this::reloadRuntimeConfig);
            InsomniaCommand command = new InsomniaCommand(config, fatigue, sleep, hallucinations, mobs, rested, this::reloadRuntimeConfig, menu);
            FatigueOverview overview = new FatigueOverview(this, api, messages, fatigue, sleep, rested, campfire);
            overview.menu(menu);
            menu.overview(overview);
            command.overview(overview);
            command.campfire(campfire);
            try {
                overview.registerPlayerMenu(config);
                core.mid("Fatigue entry registered in the Core player menu");
            } catch (RuntimeException failure) {
                core.high("Player menu entry could not be registered: " + failure);
            }

            registerCommands(command, new DreamCommand(dreams));

            getServer().getServicesManager().register(InsomniaApi.class, fatigue, this, ServicePriority.Normal);
            try {
                InsomniaWeb page = new InsomniaWeb(this, api.web(), messages, fatigue, sleep, rested, config::stageThresholds);
                page.register();
                getServer().getPluginManager().registerEvents(page, this);
                web = page;
            } catch (RuntimeException failure) {
                core.high("Web page could not be registered: " + failure);
            }
            try {
                menu.register(new FatiguePanel(this, api, messages, fatigue, sleep, rested), web);
                api.registerTutorial(this);
            } catch (RuntimeException failure) {
                api.debug().messages().high("[VystormInsomnia/VystormInsomniaPlugin] Could not register the menu and settings with Core", failure);
            }
            try {
                sleepDialog.registerSettings();
            } catch (RuntimeException failure) {
                core.high("Sleep settings could not be registered: " + failure);
            }
            try {
                InsomniaConfigEditor.register(this, api.settings().config(), messages.lang(), this::reloadRuntimeConfig);
            } catch (RuntimeException failure) {
                api.debug().messages().high("[VystormInsomnia/VystormInsomniaPlugin] Config editor registration failed", failure);
            }

            logConfigWarnings();
            nextDirtyFlushAt = System.currentTimeMillis() + config.dirtyFlushSeconds() * 1000L;
            runtime.timer(20L, 20L, this::runtimeTick);
            core.mid("Enabled; city=" + city.available() + ", bloodMoon=" + bloodMoon.available());
        } catch (RuntimeException failure) {
            if (core != null) core.high("Bootstrap failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            api.debug().messages().high("[VystormInsomnia/VystormInsomniaPlugin] VystormInsomnia start failed: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** YAML by default; MySQL on Core's shared pool when storage.type is mysql. null = refuse to start. */
    private PersistenceService createPersistence(API api) {
        StorageSettings storage = config.storage();
        java.util.concurrent.Callable<PlayerFileStore> files = () -> PlayerFileStore.open(this, api);
        if (!storage.mysql()) return new PersistenceService(this, core, files);
        return PersistenceService.mysql(this, core, files, storage, api.database().sharedReady(),
                task -> {
                    try { if (isEnabled()) runtime.now(task); }
                    catch (IllegalStateException closed) { /* plugin is being disabled */ }
                },
                reason -> {
                    api.debug().messages().high("[VystormInsomnia/VystormInsomniaPlugin] MySQL storage unavailable (" + reason
                            + "); VystormInsomnia is disabled so no player data is written elsewhere.");
                    getServer().getPluginManager().disablePlugin(this);
                });
    }

    private void logConfigWarnings() {
        for (String warning : stimulants.warnings()) core.high(warning);
        for (String warning : mobs.warnings()) core.high(warning);
    }

    static boolean requiredCoreApiAvailable() {
        try {
            if (!CoreApi.atLeast(MIN_CORE_VERSION)) return false;
            return true;
        } catch (LinkageError tooOld) {
            return false;
        }
    }

    /** /insomnia, /dream, /wake and /dreamloot through Core's command system (see InsomniaCommands). */
    private void registerCommands(InsomniaCommand command, DreamCommand dreamCommand) {
        for (var spec : at.vystorm.insomnia.command.InsomniaCommands.specs(command, dreamCommand))
            api.command().register(this, messages.lang(), spec);
    }

    static String safeCoreVersion() {
        try { return CoreApi.version(); } catch (LinkageError tooOld) { return "< 0.11.0"; }
    }

    static boolean hasClass(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private void runtimeTick() {
        long now = System.currentTimeMillis();
        if (dreams != null) tickGuard.run("dreams", now, () -> dreams.tick(now));
        tickGuard.run("stimulants", now, () -> stimulants.tick(now));
        tickGuard.run("sleep", now, () -> sleep.tick(now));
        tickGuard.run("fatigue", now, () -> fatigue.tick(now));
        tickGuard.run("rested", now, () -> rested.tick(now));
        tickGuard.run("campfire", now, () -> campfire.tick(now));
        tickGuard.run("debt-reminders", now, () -> debtReminders.tick(now));
        tickGuard.run("hallucinations", now, () -> hallucinations.tick(now));
        tickGuard.run("microsleep", now, () -> microsleep.tick(now));
        tickGuard.run("mobs", now, () -> mobs.tick(now));
        if (hud != null) tickGuard.run("hud", now, hud::tick);
        if (web != null) tickGuard.run("web", now, () -> web.tick(now));
        if (now >= nextDirtyFlushAt) {
            nextDirtyFlushAt = now + config.dirtyFlushSeconds() * 1000L;
            tickGuard.run("persistence", now, () -> persistence.flushDirty(fatigue.states()));
        }
    }

    /** @throws IllegalArgumentException when the new configuration is invalid; the old one then stays active */
    public void reloadRuntimeConfig() {
        try {
            config.reload();
        } catch (IllegalArgumentException invalid) {
            core.high("Reload rejected, previous configuration stays active: " + invalid.getMessage());
            throw invalid;
        }
        stimulants.reload();
        mobs.reload();
        if (dreams != null) dreams.reload();
        if (accelerator != null) accelerator.apply();
        if (messages != null) messages.reload();
        logConfigWarnings();
        nextDirtyFlushAt = System.currentTimeMillis() + config.dirtyFlushSeconds() * 1000L;
        core.mid("Configuration reloaded");
    }

    @Override
    public void onDisable() {
        if (runtime != null) runtime.close();
        if (accelerator != null) accelerator.shutdown();
        if (dreams != null) dreams.shutdown();
        if (microsleep != null) microsleep.shutdown();
        if (sleep != null) sleep.shutdown();
        if (mobs != null) mobs.shutdown();
        if (hallucinations != null) hallucinations.shutdown();
        if (visibility != null) visibility.clear();
        if (persistence != null && fatigue != null) {
            try { persistence.saveAll(fatigue.states()); }
            catch (RuntimeException failure) {
                if (core != null) core.high("Final persistence flush failed: " + failure.getClass().getSimpleName());
            }
        }
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) core.mid("Disabled cleanly");
    }
}
