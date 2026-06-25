package net.minecraft.client;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.BanDetails;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.UserApiService.UserFlag;
import com.mojang.authlib.minecraft.UserApiService.UserProperties;
import com.mojang.authlib.yggdrasil.FriendsService;
import com.mojang.authlib.yggdrasil.ProfileActionType;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.ClientShutdownWatchdog;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.MessageBox;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.TextInputManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.systems.TimerQuery;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.datafixers.DataFixer;
import com.mojang.jtracy.DiscontinuousFrame;
import com.mojang.jtracy.TracyClient;
import com.mojang.logging.LogUtils;
import com.mojang.realmsclient.RealmsMainScreen;
import com.mojang.realmsclient.client.RealmsClient;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.Optionull;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debugchart.ProfilerPieChart;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.OutOfMemoryScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.ChatAbilities;
import net.minecraft.client.multiplayer.chat.ChatRestriction;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.LocalPlayerResolver;
import net.minecraft.client.profiling.ClientMetricsSamplersProvider;
import net.minecraft.client.quickplay.QuickPlayLog;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GpuWarnlistManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.client.resources.DryFoliageColorReloadListener;
import net.minecraft.client.resources.FoliageColorReloadListener;
import net.minecraft.client.resources.GrassColorReloadListener;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventType;
import net.minecraft.client.telemetry.TelemetryProperty;
import net.minecraft.client.telemetry.events.GameLoadTimesEvent;
import net.minecraft.client.tutorial.Tutorial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindResolver;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Dialogs;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.Musics;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DialogTags;
import net.minecraft.util.FileUtil;
import net.minecraft.util.FileZipper;
import net.minecraft.util.MemoryReserve;
import net.minecraft.util.ModCheck;
import net.minecraft.util.NativeModuleLister;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.ContinuousProfiler;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.util.profiling.Zone;
import net.minecraft.util.profiling.metrics.profiling.ActiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import net.minecraft.util.profiling.metrics.storage.MetricsPersister;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class Minecraft extends ReentrantBlockableEventLoop<Runnable> implements WindowEventHandler, net.minecraftforge.client.extensions.IForgeMinecraft {
    private static Minecraft instance;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TICKS_PER_UPDATE = 10;
    public static final Identifier DEFAULT_FONT = Identifier.withDefaultNamespace("default");
    private static final Identifier REGIONAL_COMPLIANCIES = Identifier.withDefaultNamespace("regional_compliancies.json");
    private static final CompletableFuture<Unit> RESOURCE_RELOAD_INITIAL_TASK = CompletableFuture.completedFuture(Unit.INSTANCE);
    public static final String UPDATE_DRIVERS_ADVICE = "Please make sure you have up-to-date drivers (see aka.ms/mcdriver for instructions).";
    private final long canary = Double.doubleToLongBits(Math.PI);
    private final Path resourcePackDirectory;
    private final CompletableFuture<@Nullable ProfileResult> profileFuture;
    public final TextureManager textureManager;
    private final ShaderManager shaderManager;
    private final DataFixer fixerUpper;
    private final MonitorManager monitorManager;
    private final Window window;
    private final GpuSurface windowSurface;
    private final TextInputManager textInputManager;
    private final DeltaTracker.Timer deltaTracker = new DeltaTracker.Timer(20.0F, 0L, this::getTickTargetMillis);
    public final LevelExtractor levelExtractor;
    public final LevelRenderer levelRenderer;
    private final EntityRenderDispatcher entityRenderDispatcher;
    private final ItemModelResolver itemModelResolver;
    private final MapRenderer mapRenderer;
    public final ParticleEngine particleEngine;
    private final User user;
    public final Font font;
    public final Font fontFilterFishy;
    public final GameRenderer gameRenderer;
    public final Gui gui;
    public final Options options;
    public final DebugScreenEntryList debugEntries;
    private final HotbarManager hotbarManager;
    public final MouseHandler mouseHandler;
    public final KeyboardHandler keyboardHandler;
    private InputType lastInputType = InputType.NONE;
    public final File gameDirectory;
    private final String launchedVersion;
    private final Proxy proxy;
    private final boolean offlineDeveloperMode;
    private final LevelStorageSource levelSource;
    private final boolean demo;
    private final boolean allowsMultiplayer;
    private final boolean allowsChat;
    private final ReloadableResourceManager resourceManager;
    private final VanillaPackResources vanillaPackResources;
    private final DownloadedPackSource downloadedPackSource;
    private final PackRepository resourcePackRepository;
    private final LanguageManager languageManager;
    private final BlockColors blockColors;
    private final @Nullable TracyFrameCapture tracyFrameCapture;
    private final SoundManager soundManager;
    private final MusicManager musicManager;
    private final FontManager fontManager;
    private final GpuWarnlistManager gpuWarnlistManager;
    private final PeriodicNotificationManager regionalCompliancies = new PeriodicNotificationManager(REGIONAL_COMPLIANCIES, Minecraft::countryEqualsISO3);
    private final UserApiService userApiService;
    private final CompletableFuture<UserProperties> userPropertiesFuture;
    private final SkinManager skinManager;
    private final AtlasManager atlasManager;
    private final ModelManager modelManager;
    private final MapTextureManager mapTextureManager;
    private final Tutorial tutorial;
    private final PlayerSocialManager playerSocialManager;
    private final RemoteFriendListUpdateHandler remoteFriendListUpdateHandler;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final ClientTelemetryManager telemetryManager;
    private final ProfileKeyPairManager profileKeyPairManager;
    private final RealmsDataFetcher realmsDataFetcher;
    private final QuickPlayLog quickPlayLog;
    private final Services services;
    private final PlayerSkinRenderCache playerSkinRenderCache;
    private final TimerQuery timerQuery;
    public @Nullable MultiPlayerGameMode gameMode;
    public @Nullable ClientLevel level;
    public @Nullable LocalPlayer player;
    private @Nullable IntegratedServer singleplayerServer;
    private @Nullable Connection pendingConnection;
    private boolean isLocalServer;
    public @Nullable Entity crosshairPickEntity;
    public @Nullable HitResult hitResult;
    private int rightClickDelay;
    protected int missTime;
    private volatile boolean pause;
    private long lastNanoTime = Util.getNanos();
    private long lastTime;
    private int frames;
    private Thread gameThread;
    private volatile boolean running;
    private static int fps;
    private long frameTimeNs;
    private final FramerateLimitTracker framerateLimitTracker;
    public boolean wireframe;
    public boolean smartCull = true;
    private long lastActiveTime = Util.getMillis();
    private @Nullable CompletableFuture<Void> pendingReload;
    private int fpsPieRenderTicks;
    private final ContinuousProfiler fpsPieProfiler;
    private MetricsRecorder metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    private final ResourceLoadStateTracker reloadStateTracker = new ResourceLoadStateTracker();
    private long savedCpuDuration;
    private double gpuUtilization;
    private final GameNarrator narrator;
    private ReportingContext reportingContext;
    private final DirectoryValidator directoryValidator;
    private boolean gameLoadFinished;
    private final long clientStartTimeMs;
    private long clientTickCount;
    private final PacketProcessor packetProcessor;
    private final SimpleGizmoCollector perTickGizmos = new SimpleGizmoCollector();
    private List<SimpleGizmoCollector.GizmoInstance> drainedLatestTickGizmos = new ArrayList<>();
    private boolean windowSurfaceNeedsReconfiguring = true;
    private boolean surfaceIsInvalid = true;
    private @Nullable BackendCreationException backendCreationException;

    public Minecraft(final GameConfig gameConfig) {
        super("Client", true);
        instance = this;
        this.clientStartTimeMs = System.currentTimeMillis();
        this.gameDirectory = gameConfig.location.gameDirectory;
        File assetsDirectory = gameConfig.location.assetDirectory;
        this.resourcePackDirectory = gameConfig.location.resourcePackDirectory.toPath();
        this.launchedVersion = gameConfig.game.launchVersion;
        Path gameDirPath = this.gameDirectory.toPath();
        this.directoryValidator = LevelStorageSource.parseValidator(gameDirPath.resolve("allowed_symlinks.txt"));
        ClientPackSource clientPackSource = new ClientPackSource(gameConfig.location.getExternalAssetSource(), this.directoryValidator);
        this.downloadedPackSource = new DownloadedPackSource(this, gameDirPath.resolve("downloads"), gameConfig.user);
        RepositorySource directoryPacks = new FolderRepositorySource(
            this.resourcePackDirectory, PackType.CLIENT_RESOURCES, PackSource.DEFAULT, this.directoryValidator
        );
        this.resourcePackRepository = new PackRepository(clientPackSource, this.downloadedPackSource.createRepositorySource(), directoryPacks);
        this.vanillaPackResources = clientPackSource.getVanillaPack();
        this.proxy = gameConfig.user.proxy;
        this.offlineDeveloperMode = gameConfig.game.offlineDeveloperMode;
        YggdrasilAuthenticationService authenticationService = this.offlineDeveloperMode
            ? YggdrasilAuthenticationService.createOffline(this.proxy)
            : new YggdrasilAuthenticationService(this.proxy);
        this.services = Services.create(authenticationService, this.gameDirectory);
        this.user = gameConfig.user.user;
        this.profileFuture = this.offlineDeveloperMode
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.supplyAsync(() -> this.services.sessionService().fetchProfile(this.user.getProfileId(), true), Util.nonCriticalIoPool());
        this.userApiService = createUserApiService(authenticationService, gameConfig);
        this.userPropertiesFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return this.userApiService.fetchProperties();
            } catch (AuthenticationException e) {
                LOGGER.error("Failed to fetch user properties", e);
                return UserApiService.OFFLINE_PROPERTIES;
            }
        }, Util.nonCriticalIoPool());
        LOGGER.info("Setting user: {}", this.user.getName());
        this.demo = gameConfig.game.demo;
        this.allowsMultiplayer = !gameConfig.game.disableMultiplayer;
        this.allowsChat = !gameConfig.game.disableChat;
        this.singleplayerServer = null;
        KeybindResolver.setKeyResolver(KeyMapping::createNameSupplier);
        this.fixerUpper = DataFixers.getDataFixer();
        this.gameThread = Thread.currentThread();
        this.options = new Options(this, this.gameDirectory);
        this.debugEntries = new DebugScreenEntryList(this.gameDirectory, this.fixerUpper);
        boolean lastStartWasClean = this.options.startedCleanly;
        this.options.startedCleanly = false;
        this.options.save();
        this.running = true;
        this.tutorial = new Tutorial(this, this.options);
        this.hotbarManager = new HotbarManager(gameDirPath, this.fixerUpper);
        LOGGER.info("Backend library: {}", RenderSystem.getBackendDescription());
        DisplayData displayData = gameConfig.display;
        if (this.options.overrideHeight > 0 && this.options.overrideWidth > 0) {
            displayData = gameConfig.display.withSize(this.options.overrideWidth, this.options.overrideHeight);
        }

        if (!lastStartWasClean) {
            displayData = displayData.withFullscreen(false);
            this.options.fullscreenVideoModeString = null;
            LOGGER.warn("Detected unexpected shutdown during last game startup: resetting fullscreen mode");
        }

        Util.setTimeSource(RenderSystem.initBackendSystem());
        this.monitorManager = new MonitorManager();
        StringBuilder errorMsgBuilder = new StringBuilder("No supported graphics backend was found.");
        Window windowCandidate = null;
        GpuDevice device = null;
        PreferredGraphicsApi forcedGraphicsApi = gameConfig.game.forcedGraphicsApi;
        if (forcedGraphicsApi != null) {
            LOGGER.warn(
                "Graphics backend forced to {} by launch argument, in-game preferred graphics backend setting is ignored",
                forcedGraphicsApi.getSerializedName()
            );
        } else if (!lastStartWasClean) {
            if (this.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN) {
                LOGGER.warn("Detected unexpected shutdown during last game startup: resetting preferred graphics API to Default");
                this.options.preferredGraphicsBackend().set(PreferredGraphicsApi.DEFAULT);
                this.options.save();
            } else if (this.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.DEFAULT) {
                LOGGER.warn("Detected unexpected shutdown during last game startup: forcing preferred graphics API to OpenGL");
                this.options.preferredGraphicsBackend().set(PreferredGraphicsApi.OPENGL);
            }
        }

        PreferredGraphicsApi preferredGraphicsBackend = forcedGraphicsApi == null ? this.options.preferredGraphicsBackend().get() : forcedGraphicsApi;
        if (preferredGraphicsBackend == PreferredGraphicsApi.DEFAULT) {
            this.backendCreationException = VulkanBackend.checkBackendAvailable();
        }

        String initialWindowTitle = this.createTitle();

        for (GpuBackend backend : preferredGraphicsBackend.getBackendsToTry()) {
            try {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(131088, GLX.glfwBool(!this.options.exclusiveFullscreen().get()));
                windowCandidate = new Window(
                    this,
                    displayData,
                    this.options.fullscreenVideoModeString,
                    this.options.exclusiveFullscreen().get(),
                    initialWindowTitle,
                    this.monitorManager,
                    backend
                );
                device = windowCandidate.backend()
                    .createDevice(
                        windowCandidate.handle(),
                        (id, type) -> this.getShaderManager().getShader(id, type),
                        new GpuDebugOptions(
                            this.options.glDebugVerbosity,
                            SharedConstants.DEBUG_SYNCHRONOUS_GL_LOGS,
                            gameConfig.game.renderDebugLabels,
                            gameConfig.game.vulkanValidation
                        ),
                        this::loadCriticalShaders
                    );
                DeviceInfo deviceInfo = device.getDeviceInfo();
                int maxSize = deviceInfo.limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
                GLFW.glfwSetWindowSizeLimits(windowCandidate.handle(), -1, -1, maxSize, maxSize);
                RenderSystem.initRenderer(device);
                LOGGER.info("Using graphics backend {}, using drivers: {}", deviceInfo.backendName(), deviceInfo.driverInfo());
                LOGGER.info("Using graphics device: {} ({})", deviceInfo.name(), deviceInfo.vendorName());
                LOGGER.info("Using graphics device extensions: {}", String.join(", ", deviceInfo.underlyingExtensions()));
                break;
            } catch (BackendCreationException exception) {
                LOGGER.error("Failed to create backend {}", backend.getName(), exception);
                errorMsgBuilder.append("\n\n- Tried ").append(backend.getName()).append(": \n  ").append(exception.getMessage());
                if (this.backendCreationException == null || exception.getReason() != BackendCreationException.Reason.OPENGL_MISSING) {
                    this.backendCreationException = exception;
                }

                if (windowCandidate != null) {
                    windowCandidate.close();
                    windowCandidate = null;
                }
            }
        }

        if (windowCandidate == null) {
            String errorMsg = errorMsgBuilder.toString();
            MessageBox.error(errorMsg);
            throw new Window.WindowInitFailed(errorMsg);
        }

        this.window = windowCandidate;
        this.windowSurface = device.createSurface(this.window.handle());
        this.textInputManager = new TextInputManager(this.window);
        this.window
            .setWindowCloseCallback(
                new Runnable() {
                    private boolean threadStarted;

                    @Override
                    public void run() {
                        if (!this.threadStarted) {
                            this.threadStarted = true;
                            ClientShutdownWatchdog.startShutdownWatchdog(
                                "window close callback", false, Minecraft.this, gameConfig, Minecraft.this.gameThread.threadId()
                            );
                        }
                    }
                }
            );
        GameLoadTimesEvent.INSTANCE.endStep(TelemetryProperty.LOAD_TIME_PRE_WINDOW_MS);

        try {
            this.window.setIcon(this.vanillaPackResources, SharedConstants.getCurrentVersion().stable() ? IconSet.RELEASE : IconSet.SNAPSHOT);
        } catch (IOException e) {
            LOGGER.error("Couldn't set icon", e);
        }

        // FORGE: Move mouse and keyboard handler setup further below
        this.mouseHandler = new MouseHandler(this);
        this.keyboardHandler = new KeyboardHandler(this);
        this.options.applyGraphicsPreset(this.options.graphicsPreset().get());
        this.resourceManager = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
        net.minecraftforge.client.loading.ClientModLoader.begin(this, this.resourcePackRepository, this.resourceManager);
        this.resourcePackRepository.reload();
        this.options.loadSelectedResourcePacks(this.resourcePackRepository);
        this.languageManager = new LanguageManager(this.options.languageCode, var1 -> {
            if (this.player != null) {
                this.player.connection.updateSearchTrees();
            }
        });
        this.resourceManager.registerReloadListener(this.languageManager);
        this.textureManager = new TextureManager(this.resourceManager);
        this.resourceManager.registerReloadListener(this.textureManager);
        this.shaderManager = new ShaderManager(this.textureManager, this::triggerResourcePackRecovery);
        this.resourceManager.registerReloadListener(this.shaderManager);
        SkinTextureDownloader skinTextureDownloader = new SkinTextureDownloader(this.proxy, this.textureManager, this);
        this.skinManager = new SkinManager(assetsDirectory.toPath().resolve("skins"), this.services, skinTextureDownloader, this);
        this.levelSource = new LevelStorageSource(gameDirPath.resolve("saves"), gameDirPath.resolve("backups"), this.directoryValidator, this.fixerUpper);
        this.musicManager = new MusicManager(this);
        this.soundManager = new SoundManager(this.options);
        this.resourceManager.registerReloadListener(this.soundManager);
        this.atlasManager = new AtlasManager(this.textureManager, this.options.mipmapLevels().get());
        this.resourceManager.registerReloadListener(this.atlasManager);
        ProfileResolver localProfileResolver = new LocalPlayerResolver(this, this.services.profileResolver());
        this.playerSkinRenderCache = new PlayerSkinRenderCache(this.textureManager, this.skinManager, localProfileResolver);
        ClientMannequin.registerOverrides(this.playerSkinRenderCache);
        this.fontManager = new FontManager(this.textureManager, this.atlasManager, this.playerSkinRenderCache);
        this.font = this.fontManager.createFont();
        this.fontFilterFishy = this.fontManager.createFontFilterFishy();
        this.resourceManager.registerReloadListener(this.fontManager);
        this.updateFontOptions();
        this.resourceManager.registerReloadListener(new GrassColorReloadListener());
        this.resourceManager.registerReloadListener(new FoliageColorReloadListener());
        this.resourceManager.registerReloadListener(new DryFoliageColorReloadListener());
        this.window.setErrorSection("Startup");
        RenderSystem.setupDefaultState();
        this.window.setErrorSection("Post startup");
        this.blockColors = BlockColors.createDefault();
        this.modelManager = new ModelManager(this.blockColors, this.atlasManager, this.playerSkinRenderCache);
        this.resourceManager.registerReloadListener(this.modelManager);
        EquipmentAssetManager equipmentAssets = new EquipmentAssetManager();
        this.resourceManager.registerReloadListener(equipmentAssets);
        BlockModelResolver blockModelResolver = new BlockModelResolver(this.modelManager);
        this.itemModelResolver = new ItemModelResolver(this.modelManager);
        this.mapTextureManager = new MapTextureManager(this.textureManager);
        this.mapRenderer = new MapRenderer(this.atlasManager, this.mapTextureManager);
        FriendsService friendsService = authenticationService.createFriendsService(this.user.getAccessToken());
        this.remoteFriendListUpdateHandler = new RemoteFriendListUpdateHandler(friendsService, this);
        this.playerSocialManager = new PlayerSocialManager(this, this.userApiService, friendsService, this.remoteFriendListUpdateHandler);
        if (this.playerSocialManager.isFriendListEnabled()) {
            this.remoteFriendListUpdateHandler.start();
        }

        this.entityRenderDispatcher = new EntityRenderDispatcher(
            this,
            this.textureManager,
            blockModelResolver,
            this.itemModelResolver,
            this.mapRenderer,
            this.atlasManager,
            this.font,
            this.options,
            this.modelManager.entityModels(),
            equipmentAssets,
            this.playerSkinRenderCache
        );
        this.resourceManager.registerReloadListener(this.entityRenderDispatcher);
        this.blockEntityRenderDispatcher = new BlockEntityRenderDispatcher(
            this.font,
            this.modelManager.entityModels(),
            blockModelResolver,
            this.itemModelResolver,
            this.entityRenderDispatcher,
            this.atlasManager,
            this.playerSkinRenderCache
        );
        this.resourceManager.registerReloadListener(this.blockEntityRenderDispatcher);
        ParticleResources particleResources = new ParticleResources();
        this.resourceManager.registerReloadListener(particleResources);
        this.particleEngine = new ParticleEngine(this.level, particleResources);
        net.minecraftforge.client.ForgeHooksClient.onRegisterParticleProviders(particleResources);
        particleResources.onReload(this.particleEngine::clearParticles);
        this.gameRenderer = new GameRenderer(this, this.entityRenderDispatcher.getItemInHandRenderer(), this.modelManager);
        WindowRenderState windowRenderState = this.gameRenderer.gameRenderState().windowRenderState;
        windowRenderState.width = this.window.getWidth();
        windowRenderState.height = this.window.getHeight();
        this.levelRenderer = new LevelRenderer(
            this.entityRenderDispatcher,
            this.blockEntityRenderDispatcher,
            this.modelManager,
            this.textureManager,
            this.atlasManager,
            this.shaderManager,
            this.gameRenderer,
            this.window.getWidth(),
            this.window.getHeight()
        );
        this.levelExtractor = new LevelExtractor(this, this.gameRenderer.gameRenderState().levelRenderState, this.levelRenderer);
        net.minecraftforge.client.event.ForgeEventFactoryClient.onInitLevelRenderer();
        this.resourceManager.registerReloadListener(this.levelExtractor);
        this.resourceManager.registerReloadListener(this.levelRenderer.cloudRenderer());
        this.gpuWarnlistManager = new GpuWarnlistManager();
        this.resourceManager.registerReloadListener(this.gpuWarnlistManager);
        this.resourceManager.registerReloadListener(this.regionalCompliancies);
        this.gui = new Gui(this, new Hud(this), this.gameRenderer.gameRenderState().guiRenderState);
        this.gui.registerReloadListeners(this.resourceManager);
        // FORGE: Moved keyboard and mouse handler setup below ingame gui creation to prevent NPEs in them.
        this.mouseHandler.setup(this.window);
        this.keyboardHandler.setup(this.window);
        RealmsClient realmsClient = RealmsClient.getOrCreate(this);
        this.realmsDataFetcher = new RealmsDataFetcher(realmsClient);
        RenderSystem.setErrorCallback(this::onFullscreenError);
        RenderTarget mainRenderTarget = this.gameRenderer.mainRenderTarget();
        if (mainRenderTarget.width != this.window.getWidth() || mainRenderTarget.height != this.window.getHeight()) {
            StringBuilder message = new StringBuilder(
                "Recovering from unsupported resolution ("
                    + this.window.getWidth()
                    + "x"
                    + this.window.getHeight()
                    + ").\nPlease make sure you have up-to-date drivers (see aka.ms/mcdriver for instructions)."
            );

            try {
                List<String> messages = device.getLastDebugMessages();
                if (!messages.isEmpty()) {
                    message.append("\n\nReported GL debug messages:\n").append(String.join("\n", messages));
                }
            } catch (Throwable var27) {
            }

            this.window.setWindowed(mainRenderTarget.width, mainRenderTarget.height);
            MessageBox.error(message.toString());
        } else if (this.options.fullscreen().get() && !this.window.isFullscreen()) {
            if (lastStartWasClean) {
                this.window.toggleFullScreen();
                this.options.fullscreen().set(this.window.isFullscreen());
            } else {
                this.options.fullscreen().set(false);
            }
        }

        net.minecraftforge.client.ForgeHooksClient.initClientHooks(this, this.resourceManager);
        this.window.updateRawMouseInput(this.options.rawMouseInput().get());
        this.window.setAllowCursorChanges(this.options.allowCursorChanges().get());
        this.window.setDefaultErrorCallback();
        GLFW.glfwShowWindow(windowCandidate.handle());
        this.resizeGui();
        this.loadCriticalShaders();
        this.telemetryManager = new ClientTelemetryManager(this, this.userApiService, this.user);
        this.profileKeyPairManager = this.offlineDeveloperMode
            ? ProfileKeyPairManager.EMPTY_KEY_MANAGER
            : ProfileKeyPairManager.create(this.userApiService, this.user, gameDirPath);
        this.narrator = new GameNarrator(this);
        this.narrator.checkStatus(this.options.narrator().get() != NarratorStatus.OFF);
        this.reportingContext = ReportingContext.create(ReportEnvironment.local(), this.userApiService);
        TitleScreen.registerTextures(this.textureManager);
        LoadingOverlay.registerTextures(this.textureManager);
        this.gameRenderer.registerPanoramaTextures(this.textureManager);
        this.timerQuery = new TimerQuery();
        this.gui.gui.setScreen(new GenericMessageScreen(Component.translatable("gui.loadingMinecraft")));
        List<PackResources> packs = this.resourcePackRepository.openAllSelected();
        this.reloadStateTracker.startReload(ResourceLoadStateTracker.ReloadReason.INITIAL, packs);
        ReloadInstance reloadInstance = this.resourceManager
            .createReload(Util.backgroundExecutor().forName("resourceLoad"), this, RESOURCE_RELOAD_INITIAL_TASK, packs);
        GameLoadTimesEvent.INSTANCE.beginStep(TelemetryProperty.LOAD_TIME_LOADING_OVERLAY_MS);
        GameLoadCookie loadCookie = new GameLoadCookie(realmsClient, gameConfig.quickPlay);
        this.gui.setOverlay(net.minecraftforge.fml.loading.ImmediateWindowHandler.<LoadingOverlay>loadingOverlay(() -> this, () ->reloadInstance, maybeT -> Util.ifElse(maybeT, t -> this.rollbackResourcePacks(t, loadCookie), () -> {
            if (SharedConstants.IS_RUNNING_IN_IDE) {
                this.selfTest();
            }

            this.reloadStateTracker.finishReload();
            this.onResourceLoadFinished(loadCookie);
            net.minecraftforge.client.loading.ClientModLoader.completeModLoading();
        }), false).get());
        this.quickPlayLog = QuickPlayLog.of(gameConfig.quickPlay.logPath());
        this.framerateLimitTracker = new FramerateLimitTracker(this.options, this);
        this.fpsPieProfiler = new ContinuousProfiler(Util.timeSource(), () -> this.fpsPieRenderTicks, this.framerateLimitTracker::isHeavilyThrottled);
        if (TracyClient.isAvailable() && gameConfig.game.captureTracyImages) {
            this.tracyFrameCapture = new TracyFrameCapture();
        } else {
            this.tracyFrameCapture = null;
        }

        this.packetProcessor = new PacketProcessor(this.gameThread);
        this.telemetryManager
            .getOutsideSessionSender()
            .send(
                TelemetryEventType.GRAPHICS_CAPABILITIES,
                properties -> {
                    properties.putIfNotNull(TelemetryProperty.BACKEND_NAME, RenderSystem.getDevice().getDeviceInfo().backendName());
                    if (this.backendCreationException != null) {
                        properties.put(TelemetryProperty.BACKEND_FAILURE_MESSAGE, this.backendCreationException.getMessage());
                        properties.put(TelemetryProperty.BACKEND_FAILURE_REASON, this.backendCreationException.getReason().displayName());
                        properties.put(
                            TelemetryProperty.BACKEND_FAILURE_MISSING_CAPABILITIES, String.join(",", this.backendCreationException.getMissingCapabilities())
                        );
                    } else {
                        properties.put(TelemetryProperty.BACKEND_FAILURE_MESSAGE, "");
                        properties.put(TelemetryProperty.BACKEND_FAILURE_REASON, "");
                        properties.put(TelemetryProperty.BACKEND_FAILURE_MISSING_CAPABILITIES, "");
                    }
                }
            );
    }

    public boolean hasShiftDown() {
        Window window = this.getWindow();
        return InputConstants.isKeyDown(window, 340) || InputConstants.isKeyDown(window, 344);
    }

    public boolean hasControlDown() {
        Window window = this.getWindow();
        return InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
    }

    public boolean hasAltDown() {
        Window window = this.getWindow();
        return InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
    }

    private void onResourceLoadFinished(final @Nullable GameLoadCookie loadCookie) {
        if (!this.gameLoadFinished) {
            this.gameLoadFinished = true;
            this.onGameLoadFinished(loadCookie);
        }
    }

    private void onGameLoadFinished(final @Nullable GameLoadCookie cookie) {
        Runnable showScreen = this.gui.buildInitialScreens(cookie);
        GameLoadTimesEvent.INSTANCE.endStep(TelemetryProperty.LOAD_TIME_LOADING_OVERLAY_MS);
        GameLoadTimesEvent.INSTANCE.endStep(TelemetryProperty.LOAD_TIME_TOTAL_TIME_MS);
        GameLoadTimesEvent.INSTANCE.send(this.telemetryManager.getOutsideSessionSender());
        showScreen.run();
        this.options.startedCleanly = true;
        this.options.save();
    }

    public boolean isGameLoadFinished() {
        return this.gameLoadFinished;
    }

    private static boolean countryEqualsISO3(final Object iso3Locale) {
        try {
            return Locale.getDefault().getISO3Country().equals(iso3Locale);
        } catch (MissingResourceException e) {
            return false;
        }
    }

    public void updateTitle() {
        this.window.setTitle(this.createTitle());
    }

    private String createTitle() {
        StringBuilder builder = new StringBuilder("Minecraft");
        if (checkModStatus().shouldReportAsModified()) {
            builder.append("*");
            builder.append(" Forge");
        }

        builder.append(" ");
        builder.append(SharedConstants.getCurrentVersion().name());
        ClientPacketListener connection = this.getConnection();
        if (connection != null && connection.getConnection().isConnected()) {
            builder.append(" - ");
            ServerData server = this.getCurrentServer();
            if (this.singleplayerServer != null && !this.singleplayerServer.isPublished()) {
                builder.append(I18n.get("title.singleplayer"));
            } else if (server != null && server.isRealm()) {
                builder.append(I18n.get("title.multiplayer.realms"));
            } else if (this.singleplayerServer == null && (server == null || !server.isLan())) {
                builder.append(I18n.get("title.multiplayer.other"));
            } else {
                builder.append(I18n.get("title.multiplayer.lan"));
            }
        }

        return builder.toString();
    }

    private static UserApiService createUserApiService(final YggdrasilAuthenticationService authService, final GameConfig config) {
        if ("0".equals(config.user.user.getAccessToken())) // Forge: We use "0" in dev. Short circuit to stop exception spam.
            return UserApiService.OFFLINE;
        return config.game.offlineDeveloperMode ? UserApiService.OFFLINE : authService.createUserApiService(config.user.user.getAccessToken());
    }

    public boolean isOfflineDeveloperMode() {
        return this.offlineDeveloperMode;
    }

    public static ModCheck checkModStatus() {
        return ModCheck.identify("vanilla", ClientBrandRetriever::getClientModName, "Client", Minecraft.class);
    }

    private void loadCriticalShaders() {
        this.gameRenderer.preloadUiShader(this.vanillaPackResources.asProvider());
    }

    private void rollbackResourcePacks(final Throwable t, final @Nullable GameLoadCookie loadCookie) {
        if (this.resourcePackRepository.getSelectedPacks().stream().anyMatch(e -> !e.isRequired())) { //Forge: This caused infinite loop if any resource packs are forced. Such as mod resources. So check if we can disable any.
            this.clearResourcePacksOnError(t, null, loadCookie);
        } else {
            Util.throwAsRuntime(t);
        }
    }

    public void clearResourcePacksOnError(final Throwable t, final @Nullable Component message, final @Nullable GameLoadCookie loadCookie) {
        LOGGER.info("Caught error loading resourcepacks, removing all selected resourcepacks", t);
        this.reloadStateTracker.startRecovery(t);
        this.downloadedPackSource.onRecovery();
        this.resourcePackRepository.setSelected(Collections.emptyList());
        this.options.resourcePacks.clear();
        this.options.incompatibleResourcePacks.clear();
        this.options.save();
        this.reloadResourcePacks(true, loadCookie).thenRunAsync(() -> this.addResourcePackLoadFailToast(message), this);
    }

    private void abortResourcePackRecovery() {
        this.gui.setOverlay(null);
        if (this.level != null) {
            this.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            this.disconnectWithProgressScreen();
        }

        this.gui.gui.setScreen(new TitleScreen());
        this.addResourcePackLoadFailToast(null);
    }

    private void addResourcePackLoadFailToast(final @Nullable Component message) {
        ToastManager toastManager = this.gui.toastManager();
        SystemToast.addOrUpdate(toastManager, SystemToast.SystemToastId.PACK_LOAD_FAILURE, Component.translatable("resourcePack.load_fail"), message);
    }

    public void triggerResourcePackRecovery(final Exception exception) {
        if (!this.resourcePackRepository.isAbleToClearAnyPack()) {
            if (this.resourcePackRepository.getSelectedIds().size() <= 1) {
                LOGGER.error(LogUtils.FATAL_MARKER, exception.getMessage(), exception);
                this.emergencySaveAndCrash(new CrashReport(exception.getMessage(), exception));
            } else {
                this.schedule(this::abortResourcePackRecovery);
            }
        } else {
            this.clearResourcePacksOnError(exception, Component.translatable("resourcePack.runtime_failure"), null);
        }
    }

    public void run() {
        this.gameThread = Thread.currentThread();
        if (Runtime.getRuntime().availableProcessors() > 4) {
            this.gameThread.setPriority(10);
        }

        DiscontinuousFrame tickFrame = TracyClient.createDiscontinuousFrame("Client Tick");

        try {
            boolean oomRecovery = false;

            while (this.running) {
                try {
                    SingleTickProfiler tickProfiler = SingleTickProfiler.createTickProfiler("Renderer");
                    boolean shouldCollectFrameProfile = this.getDebugOverlay().showProfilerChart();

                    try (Profiler.Scope ignored = Profiler.use(this.constructProfiler(shouldCollectFrameProfile, tickProfiler))) {
                        this.metricsRecorder.startTick();
                        tickFrame.start();
                        RenderSystem.pollEvents();
                        this.runTick(!oomRecovery);
                        tickFrame.end();
                        this.metricsRecorder.endTick();
                    }

                    this.finishProfilers(shouldCollectFrameProfile, tickProfiler);
                } catch (OutOfMemoryError e) {
                    if (oomRecovery) {
                        throw e;
                    }

                    this.emergencySave();
                    this.gui.gui.setScreen(new OutOfMemoryScreen());
                    System.gc();
                    LOGGER.error(LogUtils.FATAL_MARKER, "Out of memory", e);
                    oomRecovery = true;
                }
            }
        } catch (ReportedException e) {
            LOGGER.error(LogUtils.FATAL_MARKER, "Reported exception thrown!", e);
            this.emergencySaveAndCrash(e.getReport());
        } catch (Throwable t) {
            LOGGER.error(LogUtils.FATAL_MARKER, "Unreported exception thrown!", t);
            this.emergencySaveAndCrash(new CrashReport("Unexpected error", t));
        }
    }

    void updateFontOptions() {
        this.fontManager.updateOptions(this.options);
    }

    private void onFullscreenError(final int error, final long description) {
        this.options.enableVsync().set(false);
        this.options.save();
    }

    public String getLaunchedVersion() {
        return this.launchedVersion;
    }

    @Override
    public void delayCrash(final CrashReport crash) {
        super.delayCrash(this.fillReport(crash));
    }

    public void emergencySaveAndCrash(final CrashReport partialReport) {
        MemoryReserve.release();
        CrashReport finalReport = this.fillReport(partialReport);
        int exitCode = saveReportAndShutdownSoundManager(this, this.gameDirectory, finalReport, -1);
        this.emergencySave();
        System.exit(exitCode);
    }

    public static void saveReport(final File gameDirectory, final CrashReport crash) {
        saveReport(gameDirectory, crash, -1);
    }

    public static int saveReport(final File gameDirectory, final CrashReport crash, final int reportExitCode) {
        Path crashDir = gameDirectory.toPath().resolve("crash-reports");
        Path crashFile = crashDir.resolve("crash-" + Util.getFilenameFormattedDateTime() + "-client.txt");

        try {
            Bootstrap.realStdoutPrintln(crash.getFriendlyReport(ReportType.CRASH));
        } catch (Throwable t) {
            LOGGER.error("Failed to print exception", t);
        }

        LOGGER.debug("Disabling console - remaining logs will be available only in log file");

        try {
            if (crash.getSaveFile() != null) {
                Bootstrap.realStdoutPrintln("#@!@# Game crashed! Crash report saved to: #@!@# " + crash.getSaveFile().toAbsolutePath());
                return reportExitCode;
            } else if (crash.saveToFile(crashFile, ReportType.CRASH)) {
                Bootstrap.realStdoutPrintln("#@!@# Game crashed! Crash report saved to: #@!@# " + crashFile.toAbsolutePath());
                return reportExitCode;
            } else {
                Bootstrap.realStdoutPrintln("#@?@# Game crashed! Crash report could not be saved. #@?@#");
                return -2;
            }
        } finally {
            Bootstrap.shutdownStdout();
        }
    }

    public static void crash(final @Nullable Minecraft minecraft, final File gameDirectory, final CrashReport crash, final int exitCode) {
        int actualExitCode = saveReportAndShutdownSoundManager(minecraft, gameDirectory, crash, exitCode);
        System.exit(actualExitCode);
    }

    public static int saveReportAndShutdownSoundManager(
        final @Nullable Minecraft minecraft, final File gameDirectory, final CrashReport crash, final int exitCode
    ) {
        int actualExitCode = saveReport(gameDirectory, crash, exitCode);
        if (minecraft != null) {
            minecraft.soundManager.emergencyShutdown();
        }

        return actualExitCode;
    }

    public boolean isEnforceUnicode() {
        return this.options.forceUnicodeFont().get();
    }

    public CompletableFuture<Void> reloadResourcePacks() {
        return this.reloadResourcePacks(false, null);
    }

    private CompletableFuture<Void> reloadResourcePacks(final boolean isRecovery, final @Nullable GameLoadCookie loadCookie) {
        if (this.pendingReload != null) {
            return this.pendingReload;
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!isRecovery && this.gui.overlay() instanceof LoadingOverlay) {
            this.pendingReload = result;
            return result;
        }

        this.resourcePackRepository.reload();
        List<PackResources> packs = this.resourcePackRepository.openAllSelected();
        if (!isRecovery) {
            this.reloadStateTracker.startReload(ResourceLoadStateTracker.ReloadReason.MANUAL, packs);
        }

        this.gui
            .setOverlay(
                new LoadingOverlay(
                    this,
                    this.resourceManager.createReload(Util.backgroundExecutor().forName("resourceLoad"), this, RESOURCE_RELOAD_INITIAL_TASK, packs),
                    maybeT -> Util.ifElse(maybeT, t -> {
                        if (isRecovery) {
                            this.downloadedPackSource.onRecoveryFailure();
                            this.abortResourcePackRecovery();
                        } else {
                            this.rollbackResourcePacks(t, loadCookie);
                        }
                    }, () -> {
                        this.levelExtractor.allChanged();
                        this.reloadStateTracker.finishReload();
                        this.downloadedPackSource.onReloadSuccess();
                        result.complete(null);
                        this.onResourceLoadFinished(loadCookie);
                    }),
                    !isRecovery
                )
            );
        return result;
    }

    private void selfTest() {
        boolean error = false;
        BlockStateModelSet blockModelSet = this.getModelManager().getBlockStateModelSet();
        BlockStateModel missingModel = blockModelSet.missingModel();

        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.getRenderShape() == RenderShape.MODEL) {
                    BlockStateModel model = blockModelSet.get(state);
                    if (model == missingModel) {
                        LOGGER.debug("Missing model for: {}", state);
                        error = true;
                    }
                }
            }
        }

        TextureAtlasSprite missingIcon = missingModel.particleMaterial().sprite();

        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                TextureAtlasSprite particleIcon = blockModelSet.getParticleMaterial(state).sprite();
                if (!state.isAir() && particleIcon == missingIcon) {
                    LOGGER.debug("Missing particle icon for: {}", state);
                }
            }
        }

        error |= TelemetryEventType.selfTest();
        error |= MenuScreens.selfTest();
        error |= EntityRenderers.validateRegistrations();
        if (error) {
            throw new IllegalStateException("Your game data is foobar, fix the errors above!");
        }
    }

    public LevelStorageSource getLevelSource() {
        return this.levelSource;
    }

    public void exitWorldAndClose() {
        try {
            LOGGER.info("Stopping!");
            if (this.level != null) {
                this.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            }

            this.disconnectWithProgressScreen();
            if (this.gui.screen() != null) {
                this.gui.screen().removed();
            }
        } finally {
            this.close();
        }
    }

    @Override
    public void close() {
        Util.shutdownTimeSource();

        try {
            this.remoteFriendListUpdateHandler.close();
            this.timerQuery.close();
            this.telemetryManager.close();
            this.regionalCompliancies.close();
            this.atlasManager.close();
            this.fontManager.close();
            this.gameRenderer.close();
            this.shaderManager.close();
            this.levelRenderer.close();
            this.soundManager.destroy();
            this.mapTextureManager.close();
            this.textureManager.close();
            this.resourceManager.close();
            if (this.tracyFrameCapture != null) {
                this.tracyFrameCapture.close();
            }

            this.narrator.destroy();
            FreeTypeUtil.destroy();
            Util.shutdownExecutors();
            this.windowSurface.close();
            RenderSystem.shutdownRenderer();
            net.minecraftforge.fml.config.ConfigTracker.forceUnload();
        } catch (Throwable t) {
            LOGGER.error("Shutdown failure!", t);
            throw t;
        } finally {
            this.window.close();
            this.monitorManager.close();
            GLFW.glfwTerminate();
        }
    }

    private void runTick(final boolean advanceGameTime) {
        this.window.setErrorSection("Pre render");
        if (this.window.shouldClose()) {
            this.stop();
        }

        if (this.pendingReload != null && !(this.gui.overlay() instanceof LoadingOverlay)) {
            CompletableFuture<Void> future = this.pendingReload;
            this.pendingReload = null;
            this.reloadResourcePacks().thenRun(() -> future.complete(null));
        }

        this.playerSocialManager.getPresenceHandler().tick();
        int ticksToDo = advanceGameTime ? this.deltaTracker.advanceGameTime(Util.getMillis()) : 0;
        ProfilerFiller profiler = Profiler.get();
        if (advanceGameTime) {
            try (Gizmos.TemporaryCollection ignored = this.collectPerTickGizmos()) {
                profiler.push("scheduledPacketProcessing");
                this.packetProcessor.processQueuedPackets();
                profiler.popPush("scheduledExecutables");
                this.runAllTasks();
                profiler.pop();
            }

            profiler.push("tick");
            if (ticksToDo > 0 && this.isLevelRunningNormally()) {
                profiler.push("textures");
                this.textureManager.tick();
                profiler.pop();
            }

            for (int i = 0; i < Math.min(10, ticksToDo); i++) {
                profiler.incrementCounter("clientTick");

                try (Gizmos.TemporaryCollection ignored = this.collectPerTickGizmos()) {
                    this.tick();
                }
            }

            if (ticksToDo > 0 && (this.level == null || this.level.tickRateManager().runsNormally())) {
                this.drainedLatestTickGizmos = this.perTickGizmos.drainGizmos();
            }

            profiler.pop();
        }

        this.window.setErrorSection("Render");

        try (Gizmos.TemporaryCollection ignored = this.levelExtractor.collectPerFrameMainThreadGizmos()) {
            profiler.push("sound");
            this.soundManager.updateSource(this.gameRenderer.mainCamera());
            profiler.popPush("mouse");
            this.mouseHandler.handleAccumulatedMovement();
        }

        try {
            profiler.popPush("frame");
            this.renderFrame(advanceGameTime);
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Render Frame");
            CrashReportCategory manualCrashDetails = report.addCategory("Render Frame Details");
            NativeModuleLister.addCrashSection(manualCrashDetails);
            throw new ReportedException(report);
        } finally {
            profiler.pop();
        }

        this.window.setErrorSection("Post render");
        boolean var28 = this.pause;
        this.pause = this.hasSingleplayerServer() && this.gui.isPausing() && !this.singleplayerServer.isPublished();
        if (!var28 && this.pause) {
            this.soundManager.pauseAllExcept(SoundSource.MUSIC, SoundSource.UI);
        }

        this.deltaTracker.updatePauseState(this.pause);
        this.deltaTracker.updateFrozenState(!this.isLevelRunningNormally());
    }

    public void renderFrame(final boolean advanceGameTime) {
        if (!this.windowSurface.isAcquired()) {
            ProfilerFiller profiler = Profiler.get();

            long renderStartTimer;
            boolean recordGpuUtilization;
            try (Gizmos.TemporaryCollection ignored = this.levelExtractor.collectPerFrameMainThreadGizmos()) {
                profiler.push("update window");
                this.window.updateFullscreenIfChanged();
                if (this.windowSurfaceNeedsReconfiguring || this.windowSurface.isSuboptimal() && !this.surfaceIsInvalid) {
                    int[] width = new int[1];
                    int[] height = new int[1];
                    GLFW.glfwGetFramebufferSize(this.window.handle(), width, height);
                    if (width[0] != 0 || height[0] != 0) {
                        GpuSurface.PresentMode presentMode = GpuSurface.PresentMode.getSupportedVsyncMode(
                            this.windowSurface.supportedPresentModes(), this.options.enableVsync().get()
                        );
                        GpuSurface.Configuration config = new GpuSurface.Configuration(width[0], height[0], presentMode);

                        try {
                            this.windowSurface.configure(config);
                            this.surfaceIsInvalid = false;
                        } catch (SurfaceException exception) {
                            LOGGER.warn("Couldn't configure surface to {}: {}", config, exception);
                            this.surfaceIsInvalid = true;
                        }
                    }

                    this.windowSurfaceNeedsReconfiguring = false;
                }

                if (!this.surfaceIsInvalid && !this.window.isMinimized()) {
                    try {
                        this.windowSurface.acquireNextTexture();
                    } catch (SurfaceException ex) {
                        LOGGER.warn("Couldn't acquire next surface texture with config {}: {}", this.windowSurface.currentConfiguration(), ex);
                        this.surfaceIsInvalid = true;
                        this.windowSurfaceNeedsReconfiguring = true;
                    }
                }

                profiler.popPush("update");
                this.deltaTracker.advanceRealTime(Util.getMillis());
                if (!this.debugEntries.isCurrentlyEnabled(DebugScreenEntries.GPU_UTILIZATION) && !this.metricsRecorder.isRecording()) {
                    recordGpuUtilization = false;
                    this.gpuUtilization = 0.0;
                } else {
                    recordGpuUtilization = this.timerQuery.getStatus() == TimerQuery.Status.NOT_RECORDING;
                    if (recordGpuUtilization) {
                        this.timerQuery.beginProfile();
                    }
                }

                renderStartTimer = Util.getNanos();
                this.pauseIfInactive();
                this.gui.update();
                if (this.isGameLoadFinished() && advanceGameTime && this.level != null) {
                    this.level.update();
                }

                this.gameRenderer.update(this.deltaTracker);
                float worldPartialTicks = this.deltaTracker.getGameTimeDeltaPartialTick(false);
                this.pick(worldPartialTicks);
                profiler.popPush("extract");
                this.gameRenderer.gameRenderState().framerateLimit = this.framerateLimitTracker.getFramerateLimit();
                this.gameRenderer.extract(this.deltaTracker, advanceGameTime);
            }

            try (Gizmos.TemporaryCollection ignored = this.levelRenderer.collectPerFrameRenderThreadGizmos()) {
                profiler.popPush("gpuAsync");
                RenderSystem.executePendingTasks();
                profiler.pop();
                net.minecraftforge.client.event.ForgeEventFactoryClient.onRenderTickStart(this.deltaTracker);
                this.gameRenderer.render(this.deltaTracker, advanceGameTime);
                net.minecraftforge.client.event.ForgeEventFactoryClient.onRenderTickEnd(this.deltaTracker);
            }

            profiler.push("present");
            if (this.windowSurface.isAcquired()) {
                GpuTextureView colorTexture = this.gameRenderer.mainRenderTarget().getColorTextureView();
                if (colorTexture == null) {
                    throw new IllegalStateException("Can't blit to screen, color texture doesn't exist yet");
                }

                this.windowSurface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), colorTexture);
            }

            this.frameTimeNs = Util.getNanos() - renderStartTimer;
            if (recordGpuUtilization) {
                this.timerQuery.endProfile();
            }

            profiler.popPush("swapBuffers");
            if (this.tracyFrameCapture != null) {
                this.tracyFrameCapture.upload();
                this.tracyFrameCapture.capture(this.gameRenderer.mainRenderTarget());
            }

            RenderSystem.getDevice().createCommandEncoder().submit();
            if (this.windowSurface.isAcquired()) {
                this.windowSurface.present();
            }

            if (this.tracyFrameCapture != null) {
                this.tracyFrameCapture.endFrame();
            }

            RenderSystem.getDynamicUniforms().reset();
            this.levelRenderer.endFrame();
            profiler.popPush("frameLimiter");
            int framerateLimit = this.gameRenderer.gameRenderState().framerateLimit;
            if (framerateLimit < 260) {
                FramerateLimiter.limitDisplayFPS(framerateLimit);
            }

            profiler.popPush("fpsUpdate");
            this.frames++;
            long currentTime = Util.getNanos();
            long frameDuration = currentTime - this.lastNanoTime;
            if (recordGpuUtilization) {
                this.savedCpuDuration = frameDuration;
            }

            this.getDebugOverlay().logFrameDuration(frameDuration);
            this.lastNanoTime = currentTime;

            for (this.gpuUtilization = this.timerQuery.get() * 100.0 / this.savedCpuDuration; Util.getMillis() >= this.lastTime + 1000L; this.frames = 0) {
                fps = this.frames;
                this.lastTime += 1000L;
            }

            profiler.pop();
        }
    }

    private void pauseIfInactive() {
        if (!this.window.isFocused() && this.options.pauseOnLostFocus) {
            if (Util.getMillis() - this.lastActiveTime > 500L) {
                this.pauseGame(false);
            }
        } else {
            this.lastActiveTime = Util.getMillis();
        }
    }

    private ProfilerFiller constructProfiler(final boolean shouldCollectFrameProfile, final @Nullable SingleTickProfiler tickProfiler) {
        if (!shouldCollectFrameProfile) {
            this.fpsPieProfiler.disable();
            if (!this.metricsRecorder.isRecording() && tickProfiler == null) {
                return InactiveProfiler.INSTANCE;
            }
        }

        ProfilerFiller result;
        if (shouldCollectFrameProfile) {
            if (!this.fpsPieProfiler.isEnabled()) {
                this.fpsPieRenderTicks = 0;
                this.fpsPieProfiler.enable();
            }

            this.fpsPieRenderTicks++;
            result = this.fpsPieProfiler.getFiller();
        } else {
            result = InactiveProfiler.INSTANCE;
        }

        if (this.metricsRecorder.isRecording()) {
            result = ProfilerFiller.combine(result, this.metricsRecorder.getProfiler());
        }

        return SingleTickProfiler.decorateFiller(result, tickProfiler);
    }

    private void finishProfilers(final boolean shouldCollectFrameProfile, final @Nullable SingleTickProfiler tickProfiler) {
        if (tickProfiler != null) {
            tickProfiler.endTick();
        }

        ProfilerPieChart profilerPieChart = this.getDebugOverlay().getProfilerPieChart();
        if (shouldCollectFrameProfile) {
            profilerPieChart.setPieChartResults(this.fpsPieProfiler.getResults());
        } else {
            profilerPieChart.setPieChartResults(null);
        }
    }

    @Override
    public void framebufferSizeChanged() {
        this.invalidateSurfaceConfiguration();
        this.resizeGui();
    }

    @Override
    public void resizeGui() {
        int guiScale = this.window.calculateScale(this.options.guiScale().get(), this.isEnforceUnicode());
        this.window.setGuiScale(guiScale);
        if (this.gui.screen() != null) {
            this.gui.screen().resize(this.window.getGuiScaledWidth(), this.window.getGuiScaledHeight());
            this.gui.resizeLayers(this.window.getGuiScaledWidth(), this.window.getGuiScaledHeight());
        }

        this.mouseHandler.setIgnoreFirstMove();
    }

    @Override
    public void cursorEntered() {
        this.mouseHandler.cursorEntered();
    }

    public int getFps() {
        return fps;
    }

    public long getFrameTimeNs() {
        return this.frameTimeNs;
    }

    public void sendLowDiskSpaceWarning() {
        this.execute(() -> SystemToast.onLowDiskSpace(this));
    }

    private void emergencySave() {
        MemoryReserve.release();

        try {
            if (this.isLocalServer && this.singleplayerServer != null) {
                this.singleplayerServer.halt(true);
            }

            this.disconnectWithSavingScreen();
        } catch (Throwable var2) {
        }

        System.gc();
    }

    public boolean debugClientMetricsStart(final Consumer<Component> debugFeedback) {
        if (this.metricsRecorder.isRecording()) {
            this.debugClientMetricsStop();
            return false;
        }

        Consumer<ProfileResults> onStopped = results -> {
            if (results != EmptyProfileResults.EMPTY) {
                int ticks = results.getTickDuration();
                double durationInSeconds = (double)results.getNanoDuration() / TimeUtil.NANOSECONDS_PER_SECOND;
                this.execute(
                    () -> debugFeedback.accept(
                        Component.translatable(
                            "commands.debug.stopped",
                            String.format(Locale.ROOT, "%.2f", durationInSeconds),
                            ticks,
                            String.format(Locale.ROOT, "%.2f", ticks / durationInSeconds)
                        )
                    )
                );
            }
        };
        Consumer<Path> onFinished = profilePath -> {
            Component profilePathComponent = Component.literal(profilePath.toString())
                .withStyle(ChatFormatting.UNDERLINE)
                .withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(profilePath.getParent())));
            this.execute(() -> debugFeedback.accept(Component.translatable("debug.profiling.stop", profilePathComponent)));
        };
        SystemReport systemReport = fillSystemReport(new SystemReport(), this, this.languageManager, this.launchedVersion, this.options);
        Consumer<List<Path>> profileReports = logs -> {
            Path profilePath = this.archiveProfilingReport(systemReport, logs);
            onFinished.accept(profilePath);
        };
        Consumer<Path> whenClientMetricsRecordingFinished;
        if (this.singleplayerServer == null) {
            whenClientMetricsRecordingFinished = path -> profileReports.accept(ImmutableList.of(path));
        } else {
            this.singleplayerServer.fillSystemReport(systemReport);
            CompletableFuture<Path> clientMetricRecordingResult = new CompletableFuture<>();
            CompletableFuture<Path> serverMetricRecordingResult = new CompletableFuture<>();
            CompletableFuture.allOf(clientMetricRecordingResult, serverMetricRecordingResult)
                .thenRunAsync(
                    () -> profileReports.accept(ImmutableList.of(clientMetricRecordingResult.join(), serverMetricRecordingResult.join())), Util.ioPool()
                );
            this.singleplayerServer.startRecordingMetrics(ignored -> {}, serverMetricRecordingResult::complete);
            whenClientMetricsRecordingFinished = clientMetricRecordingResult::complete;
        }

        this.metricsRecorder = ActiveMetricsRecorder.createStarted(
            new ClientMetricsSamplersProvider(Util.timeSource(), this.levelRenderer, this.levelExtractor),
            Util.timeSource(),
            Util.ioPool(),
            new MetricsPersister("client"),
            results -> {
                this.metricsRecorder = InactiveMetricsRecorder.INSTANCE;
                onStopped.accept(results);
            },
            whenClientMetricsRecordingFinished
        );
        return true;
    }

    private void debugClientMetricsStop() {
        this.metricsRecorder.end();
        if (this.singleplayerServer != null) {
            this.singleplayerServer.finishRecordingMetrics();
        }
    }

    private void debugClientMetricsCancel() {
        this.metricsRecorder.cancel();
        if (this.singleplayerServer != null) {
            this.singleplayerServer.cancelRecordingMetrics();
        }
    }

    private Path archiveProfilingReport(final SystemReport systemReport, final List<Path> profilingResultPaths) {
        String levelName;
        if (this.isLocalServer()) {
            levelName = this.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            ServerData server = this.getCurrentServer();
            levelName = server != null ? server.name : "unknown";
        }

        Path archivePath;
        try {
            String profilingName = String.format(
                Locale.ROOT, "%s-%s-%s", Util.getFilenameFormattedDateTime(), levelName, SharedConstants.getCurrentVersion().id()
            );
            String zipFileName = FileUtil.findAvailableName(MetricsPersister.PROFILING_RESULTS_DIR, profilingName, ".zip");
            archivePath = MetricsPersister.PROFILING_RESULTS_DIR.resolve(zipFileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (FileZipper fileZipper = new FileZipper(archivePath)) {
            fileZipper.add(Paths.get("system.txt"), systemReport.toLineSeparatedString());
            fileZipper.add(Paths.get("client").resolve(this.options.getFile().getName()), this.options.dumpOptionsForReport());
            profilingResultPaths.forEach(fileZipper::add);
        } finally {
            for (Path path : profilingResultPaths) {
                try {
                    FileUtils.forceDelete(path.toFile());
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete temporary profiling result {}", path, e);
                }
            }
        }

        return archivePath;
    }

    public void stop() {
        if (this.isRunning()) net.minecraftforge.event.ForgeEventFactory.onGameShuttingDown();
        this.running = false;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void pauseGame(final boolean suppressPauseMenuIfWeReallyArePausing) {
        boolean canGameReallyBePaused = this.hasSingleplayerServer() && !this.singleplayerServer.isPublished();
        this.gui.setPauseScreen(suppressPauseMenuIfWeReallyArePausing, canGameReallyBePaused);
    }

    private void continueAttack(final boolean down) {
        if (!down) {
            this.missTime = 0;
        }

        if (this.missTime <= 0 && !this.player.isUsingItem()) {
            ItemStack heldItem = this.player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!heldItem.has(DataComponents.PIERCING_WEAPON)) {
                if (down && this.hitResult != null && this.hitResult.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult blockHit = (BlockHitResult)this.hitResult;
                    BlockPos pos = blockHit.getBlockPos();
                    if (!this.level.isEmptyBlock(pos)) {
                        var inputEvent = new net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered(0, this.options.keyAttack, InteractionHand.MAIN_HAND);
                        if (net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered.BUS.post(inputEvent)) {
                           if (inputEvent.shouldSwingHand()) {
                              this.level.addBreakingBlockEffect(pos, blockHit);
                              this.player.swing(InteractionHand.MAIN_HAND);
                           }
                           return;
                        }
                        Direction direction = blockHit.getDirection();
                        if (this.gameMode.continueDestroyBlock(pos, direction) && inputEvent.shouldSwingHand()) {
                            this.level.addBreakingBlockEffect(pos, blockHit);
                            this.player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                } else {
                    this.gameMode.stopDestroyBlock();
                }
            }
        }
    }

    private boolean startAttack() {
        if (this.missTime > 0) {
            return false;
        }

        if (this.hitResult == null) {
            LOGGER.error("Null returned as 'hitResult', this shouldn't happen!");
            if (this.gameMode.hasMissTime()) {
                this.missTime = 10;
            }

            return false;
        } else {
            if (this.player.isHandsBusy()) {
                return false;
            }

            if (this.gameMode.isSpectator()) {
                if (this.hitResult instanceof EntityHitResult entityHitResult) {
                    this.gameMode.spectate(entityHitResult.getEntity());
                } else {
                    this.gameMode.spectatorNoAction();
                }

                return true;
            } else {
                ItemStack heldItem = this.player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!heldItem.isItemEnabled(this.level.enabledFeatures())) {
                    return false;
                }

                if (this.player.cannotAttackWithItem(heldItem, 0)) {
                    return false;
                }

                boolean endAttack = false;
                PiercingWeapon piercingWeapon = heldItem.get(DataComponents.PIERCING_WEAPON);
                if (piercingWeapon != null) {
                    this.gameMode.piercingAttack(piercingWeapon);
                    this.player.swing(InteractionHand.MAIN_HAND);
                    return true;
                }

                var inputEvent = new net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered(0, this.options.keyAttack, InteractionHand.MAIN_HAND);
                if (!net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered.BUS.post(inputEvent))
                switch (this.hitResult.getType()) {
                    case ENTITY:
                        AttackRange customItemRange = heldItem.get(DataComponents.ATTACK_RANGE);
                        if (customItemRange == null || customItemRange.isInRange(this.player, this.hitResult.getLocation())) {
                            this.gameMode.attack(this.player, ((EntityHitResult)this.hitResult).getEntity());
                        }
                        break;
                    case BLOCK:
                        BlockHitResult blockHit = (BlockHitResult)this.hitResult;
                        BlockPos pos = blockHit.getBlockPos();
                        if (!this.level.isEmptyBlock(pos)) {
                            this.gameMode.startDestroyBlock(pos, blockHit.getDirection());
                            if (this.level.getBlockState(pos).isAir()) {
                                endAttack = true;
                            }
                            break;
                        }
                    case MISS:
                        if (this.gameMode.hasMissTime()) {
                            this.missTime = 10;
                        }

                        this.player.resetAttackStrengthTicker();
                        net.minecraftforge.event.ForgeEventFactory.onLeftClickEmpty(this.player);
                }

                if (inputEvent.shouldSwingHand())
                this.player.swing(InteractionHand.MAIN_HAND);
                return endAttack;
            }
        }
    }

    private void startUseItem() {
        if (!this.gameMode.isDestroying()) {
            this.rightClickDelay = 4;
            if (!this.player.isHandsBusy()) {
                if (this.hitResult == null) {
                    LOGGER.warn("Null returned as 'hitResult', this shouldn't happen!");
                }

                for (InteractionHand hand : InteractionHand.values()) {
                    var inputEvent = new net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered(1, this.options.keyUse, hand);
                    if (net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered.BUS.post(inputEvent)) {
                       if (inputEvent.shouldSwingHand()) this.player.swing(hand);
                       return;
                    }

                    ItemStack heldItem = this.player.getItemInHand(hand);
                    if (!heldItem.isItemEnabled(this.level.enabledFeatures())) {
                        return;
                    }

                    if (this.hitResult != null) {
                        switch (this.hitResult.getType()) {
                            case ENTITY:
                                EntityHitResult entityHit = (EntityHitResult)this.hitResult;
                                Entity entity = entityHit.getEntity();
                                if (!this.level.getWorldBorder().isWithinBounds(entity.blockPosition())) {
                                    return;
                                }

                                if (this.player.isWithinEntityInteractionRange(entity, 0.0)
                                    && this.gameMode.interact(this.player, entity, entityHit, hand) instanceof InteractionResult.Success success) {
                                    if (success.swingSource() == InteractionResult.SwingSource.CLIENT && inputEvent.shouldSwingHand()) {
                                        this.player.swing(hand);
                                    }

                                    return;
                                }
                                break;
                            case BLOCK:
                                BlockHitResult blockHit = (BlockHitResult)this.hitResult;
                                int oldCount = heldItem.getCount();
                                InteractionResult useResult = this.gameMode.useItemOn(this.player, hand, blockHit);
                                if (useResult instanceof InteractionResult.Success success) {
                                    if (success.swingSource() == InteractionResult.SwingSource.CLIENT && inputEvent.shouldSwingHand()) {
                                        this.player.swing(hand);
                                        if (!heldItem.isEmpty() && (heldItem.getCount() != oldCount || this.player.hasInfiniteMaterials())) {
                                            this.gameRenderer.itemInHandRenderer.itemUsed(hand);
                                        }
                                    }

                                    return;
                                }

                                if (useResult instanceof InteractionResult.Fail) {
                                    return;
                                }
                        }
                    }

                    if (heldItem.isEmpty() && (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS))
                       net.minecraftforge.event.ForgeEventFactory.onRightClickEmpty(this.player, hand);

                    if (!heldItem.isEmpty() && this.gameMode.useItem(this.player, hand) instanceof InteractionResult.Success success) {
                        if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                            this.player.swing(hand);
                        }

                        this.gameRenderer.itemInHandRenderer.itemUsed(hand);
                        return;
                    }
                }
            }
        }
    }

    public MusicManager getMusicManager() {
        return this.musicManager;
    }

    public void tick() {
        this.clientTickCount++;
        if (this.level != null && !this.pause) {
            this.level.tickRateManager().tick();
        }

        if (this.rightClickDelay > 0) {
            this.rightClickDelay--;
        }

        ProfilerFiller profiler = Profiler.get();
        net.minecraftforge.event.ForgeEventFactory.onPreClientTick();

        profiler.push("gameMode");
        if (!this.pause && this.level != null) {
            this.gameMode.tick();
        }

        profiler.pop();
        this.pick(1.0F);
        this.tutorial.onLookAt(this.level, this.hitResult);
        profiler.push("gui");
        this.textInputManager.tick();
        this.gui.tick();
        if (this.gui.screen() != null) {
            this.missTime = 10000;
        }

        if (this.gui.overlay() == null && this.gui.screen() == null) {
            profiler.popPush("Keybindings");
            this.handleKeybinds();
            if (this.missTime > 0) {
                this.missTime--;
            }
        }

        if (this.level != null) {
            if (!this.pause) {
                profiler.popPush("gameRenderer");
                this.gameRenderer.tick();
                profiler.popPush("entities");
                this.level.tickEntities();
                profiler.popPush("blockEntities");
                this.level.tickBlockEntities();
            }
        } else if (this.gameRenderer.currentPostEffect() != null) {
            this.gameRenderer.clearPostEffect();
        }

        this.musicManager.tick();
        this.soundManager.tick(this.pause);
        if (this.level != null) {
            if (!this.pause) {
                profiler.popPush("level");
                if (!this.options.joinedFirstServer && this.isMultiplayerServer()) {
                    this.gui.addSocialInteractionsToast();
                    this.options.joinedFirstServer = true;
                    this.options.save();
                }

                this.tutorial.tick();

                net.minecraftforge.event.ForgeEventFactory.onPreLevelTick(this.level, () -> true);
                try {
                    this.level.tick(() -> true);
                } catch (Throwable t) {
                    CrashReport report = CrashReport.forThrowable(t, "Exception in world tick");
                    if (this.level == null) {
                        CrashReportCategory levelCategory = report.addCategory("Affected level");
                        levelCategory.setDetail("Problem", "Level is null!");
                    } else {
                        this.level.fillReportDetails(report);
                    }

                    throw new ReportedException(report);
                }
                net.minecraftforge.event.ForgeEventFactory.onPostLevelTick(this.level, () -> true);
            }

            profiler.popPush("animateTick");
            if (!this.pause && this.isLevelRunningNormally()) {
                this.level.animateTick(this.player.getBlockX(), this.player.getBlockY(), this.player.getBlockZ());
            }

            profiler.popPush("particles");
            if (!this.pause && this.isLevelRunningNormally()) {
                this.particleEngine.tick();
            }

            ClientPacketListener connection = this.getConnection();
            if (connection != null && !this.pause) {
                connection.send(ServerboundClientTickEndPacket.INSTANCE);
            }
        } else if (this.pendingConnection != null) {
            profiler.popPush("pendingConnection");
            this.pendingConnection.tick();
        }

        profiler.popPush("keyboard");
        this.keyboardHandler.tick();
        profiler.pop();
        net.minecraftforge.event.ForgeEventFactory.onPostClientTick();
    }

    private boolean isLevelRunningNormally() {
        return this.level == null || this.level.tickRateManager().runsNormally();
    }

    public boolean isMultiplayerServer() {
        return !this.isLocalServer || this.singleplayerServer != null && this.singleplayerServer.isPublished();
    }

    private void handleKeybinds() {
        while (this.options.keyTogglePerspective.consumeClick()) {
            CameraType previous = this.options.getCameraType();
            this.options.setCameraType(this.options.getCameraType().cycle());
            if (previous.isFirstPerson() != this.options.getCameraType().isFirstPerson()) {
                this.gameRenderer.checkEntityPostEffect(this.options.getCameraType().isFirstPerson() ? this.getCameraEntity() : null);
            }
        }

        while (this.options.keySmoothCamera.consumeClick()) {
            this.options.smoothCamera = !this.options.smoothCamera;
        }

        this.gui.handleKeybinds();

        while (this.options.keyToggleSpectatorShaderEffects.consumeClick()) {
            this.gameRenderer.togglePostEffect();
        }

        for (int i = 0; i < 9; i++) {
            boolean savePressed = this.options.keySaveHotbarActivator.isDown();
            boolean loadPressed = this.options.keyLoadHotbarActivator.isDown();
            if (this.options.keyHotbarSlots[i].consumeClick()) {
                if (this.player.isSpectator()) {
                    this.gui.hud.getSpectatorGui().onHotbarSelected(i);
                } else if (!this.player.hasInfiniteMaterials() || this.gui.screen() != null || !loadPressed && !savePressed) {
                    this.player.getInventory().setSelectedSlot(i);
                } else {
                    CreativeModeInventoryScreen.handleHotbarLoadOrSave(this, i, loadPressed, savePressed);
                }
            }
        }

        while (this.options.keyInventory.consumeClick()) {
            if (this.gameMode.isServerControlledInventory()) {
                this.player.sendOpenInventory();
            } else {
                this.tutorial.onOpenInventory();
                this.gui.gui.setScreen(new InventoryScreen(this.player));
            }
        }

        while (this.options.keyQuickActions.consumeClick()) {
            this.getQuickActionsDialog().ifPresent(dialog -> this.player.connection.showDialog((Holder<Dialog>)dialog, this.gui.screen()));
        }

        while (this.options.keySwapOffhand.consumeClick()) {
            if (!this.player.isSpectator()) {
                this.getConnection()
                    .send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            }
        }

        while (this.options.keyDrop.consumeClick()) {
            if (!this.player.isSpectator() && this.player.drop(this.hasControlDown())) {
                this.player.swing(InteractionHand.MAIN_HAND);
            }
        }

        boolean instantAttack = false;
        if (this.player.isUsingItem()) {
            if (!this.options.keyUse.isDown()) {
                this.gameMode.releaseUsingItem(this.player);
            }

            while (this.options.keyAttack.consumeClick()) {
            }

            while (this.options.keyUse.consumeClick()) {
            }

            while (this.options.keyPickItem.consumeClick()) {
            }
        } else {
            while (this.options.keyAttack.consumeClick()) {
                instantAttack |= this.startAttack();
            }

            while (this.options.keyUse.consumeClick()) {
                this.startUseItem();
            }

            while (this.options.keyPickItem.consumeClick()) {
                this.pickBlockOrEntity();
            }

            if (this.player.isSpectator()) {
                while (this.options.keySpectatorHotbar.consumeClick()) {
                    this.gui.hud.getSpectatorGui().onHotbarActionKeyPressed();
                }
            }
        }

        if (this.options.keyUse.isDown() && this.rightClickDelay == 0 && !this.player.isUsingItem()) {
            this.startUseItem();
        }

        this.continueAttack(this.gui.screen() == null && !instantAttack && this.options.keyAttack.isDown() && this.mouseHandler.isMouseGrabbed());
    }

    private Optional<Holder<Dialog>> getQuickActionsDialog() {
        Registry<Dialog> dialogRegistry = this.player.connection.registryAccess().lookupOrThrow(Registries.DIALOG);
        return dialogRegistry.get(DialogTags.QUICK_ACTIONS).flatMap(quickActions -> {
            if (quickActions.size() == 0) {
                return Optional.empty();
            } else {
                return quickActions.size() == 1 ? Optional.of(quickActions.get(0)) : dialogRegistry.get(Dialogs.QUICK_ACTIONS);
            }
        });
    }

    public ClientTelemetryManager getTelemetryManager() {
        return this.telemetryManager;
    }

    public MetricsRecorder getMetricsRecorder() {
        return this.metricsRecorder;
    }

    public double getGpuUtilization() {
        return this.gpuUtilization;
    }

    public ProfileKeyPairManager getProfileKeyPairManager() {
        return this.profileKeyPairManager;
    }

    public WorldOpenFlows createWorldOpenFlows() {
        return new WorldOpenFlows(this, this.levelSource);
    }

    public void doWorldLoad(
        final LevelStorageSource.LevelStorageAccess levelSourceAccess,
        final PackRepository packRepository,
        final WorldStem worldStem,
        final Optional<GameRules> gameRules,
        final boolean newWorld
    ) {
        this.disconnectWithProgressScreen();
        Instant worldLoadStart = Instant.now();
        LevelLoadTracker loadTracker = new LevelLoadTracker(newWorld ? 500L : 0L);
        LevelLoadingScreen screen = new LevelLoadingScreen(loadTracker, LevelLoadingScreen.Reason.OTHER);
        this.gui.gui.setScreen(screen);
        int chunkStatusViewRadius = Math.max(5, 3) + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1;

        try {
            levelSourceAccess.saveDataTag(worldStem.worldDataAndGenSettings().data());
            LevelLoadListener loadListener = LevelLoadListener.compose(loadTracker, LoggingLevelLoadListener.forSingleplayer());
            this.singleplayerServer = MinecraftServer.spin(
                thread -> new IntegratedServer(thread, this, levelSourceAccess, packRepository, worldStem, gameRules, this.services, loadListener)
            );
            loadTracker.setServerChunkStatusView(this.singleplayerServer.createChunkLoadStatusView(chunkStatusViewRadius));
            this.isLocalServer = true;
            this.updateReportEnvironment(ReportEnvironment.local());
            this.quickPlayLog
                .setWorldData(QuickPlayLog.Type.SINGLEPLAYER, levelSourceAccess.getLevelId(), worldStem.worldDataAndGenSettings().data().getLevelName());
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Starting integrated server");
            CrashReportCategory category = report.addCategory("Starting integrated server");
            category.setDetail("Level ID", levelSourceAccess.getLevelId());
            category.setDetail("Level Name", () -> worldStem.worldDataAndGenSettings().data().getLevelName());
            throw new ReportedException(report);
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("waitForServer");
        long tickLengthNs = TimeUnit.SECONDS.toNanos(1L) / 60L;

        while (!this.singleplayerServer.isReady() || this.gui.overlay() != null) {
            long finishTime = Util.getNanos() + tickLengthNs;
            screen.tick();
            if (this.gui.overlay() != null) {
                this.gui.overlay().tick();
            }

            this.renderFrame(false);
            this.runAllTasks();
            this.managedBlock(() -> Util.getNanos() > finishTime);
        }

        profiler.pop();
        Duration worldLoadDuration = Duration.between(worldLoadStart, Instant.now());
        SocketAddress socketAddress = this.singleplayerServer.getConnection().startMemoryChannel();
        Connection connection = Connection.connectToLocalServer(socketAddress);
        connection.initiateServerboundPlayConnection(
            socketAddress.toString(),
            0,
            new ClientHandshakePacketListenerImpl(connection, this, null, null, newWorld, worldLoadDuration, var0 -> {}, loadTracker, null)
        );
        connection.send(new ServerboundHelloPacket(this.getUser().getName(), this.getUser().getProfileId()));
        this.pendingConnection = connection;
        this.getPlayerSocialManager().getPresenceHandler().tryUpdatePresence();
    }

    public void setLevel(final ClientLevel level) {
        if (this.level != null) net.minecraftforge.event.ForgeEventFactory.onLevelUnload(this.level);
        this.level = level;
        this.updateLevelInEngines(level);
    }

    public void disconnectFromWorld(final Component message) {
        boolean localServer = this.isLocalServer();
        ServerData currentServer = this.getCurrentServer();
        if (this.level != null) {
            this.level.disconnect(message);
        }

        if (localServer) {
            this.disconnectWithSavingScreen();
        } else {
            this.disconnectWithProgressScreen();
        }

        TitleScreen titleScreen = new TitleScreen();
        if (localServer) {
            this.gui.gui.setScreen(titleScreen);
        } else if (currentServer != null && currentServer.isRealm()) {
            this.gui.gui.setScreen(new RealmsMainScreen(titleScreen));
        } else {
            this.gui.gui.setScreen(new JoinMultiplayerScreen(titleScreen));
        }
    }

    public void disconnectWithSavingScreen() {
        this.disconnect(new GenericMessageScreen(Gui.SAVING_LEVEL), false);
    }

    public void disconnectWithProgressScreen() {
        this.disconnectWithProgressScreen(true);
    }

    public void disconnectWithProgressScreen(final boolean stopSound) {
        this.disconnect(new ProgressScreen(true), false, stopSound);
    }

    public void disconnect(final Screen screen, final boolean keepResourcePacks) {
        this.disconnect(screen, keepResourcePacks, true);
    }

    public void disconnect(final Screen screen, final boolean keepResourcePacks, final boolean stopSound) {
        ClientPacketListener connection = this.getConnection();
        if (connection != null) {
            this.dropAllTasks();
            connection.close();
            if (!keepResourcePacks) {
                this.clearDownloadedResourcePacks();
            }
        }

        this.playerSocialManager.stopOnlineMode();
        if (this.metricsRecorder.isRecording()) {
            this.debugClientMetricsCancel();
        }

        IntegratedServer server = this.singleplayerServer;
        this.singleplayerServer = null;
        this.gameRenderer.resetData();
        net.minecraftforge.client.event.ForgeEventFactoryClient.firePlayerLogout(this.gameMode, this.player);
        this.gameMode = null;
        this.narrator.clear();
        this.gui.setClientLevelTeardownInProgress(true);

        try {
            if (this.level != null) {
                this.gui.hud.onDisconnected();
                net.minecraftforge.event.ForgeEventFactory.onLevelUnload(this.level);
            }

            this.level = null;
            if (server != null) {
                server.halt(false);
                this.gui.gui.setScreen(new GenericMessageScreen(Gui.SAVING_LEVEL));
                ProfilerFiller profiler = Profiler.get();
                profiler.push("waitForServer");

                while (!server.isShutdown()) {
                    this.renderFrame(false);
                }

                profiler.pop();
                net.minecraftforge.client.ForgeHooksClient.handleClientLevelClosing(this.level);
            }

            this.setScreenAndShow(screen);
            this.isLocalServer = false;
            this.updateLevelInEngines(null, stopSound);
            this.player = null;
        } finally {
            this.gui.setClientLevelTeardownInProgress(false);
        }
    }

    public void clearDownloadedResourcePacks() {
        this.downloadedPackSource.cleanupAfterDisconnect();
        this.runAllTasks();
    }

    public void clearClientLevel(final Screen screen) {
        ClientPacketListener connection = this.getConnection();
        if (connection != null) {
            connection.clearLevel();
        }

        if (this.metricsRecorder.isRecording()) {
            this.debugClientMetricsCancel();
        }

        this.gameRenderer.resetData();
        this.gameMode = null;
        this.narrator.clear();
        this.gui.setClientLevelTeardownInProgress(true);

        try {
            this.setScreenAndShow(screen);
            this.gui.hud.onDisconnected();
            this.level = null;
            this.updateLevelInEngines(null);
            this.player = null;
        } finally {
            this.gui.setClientLevelTeardownInProgress(false);
        }
    }

    public void setScreenAndShow(final Screen screen) {
        try (Zone ignored = Profiler.get().zone("forcedTick")) {
            this.gui.gui.setScreen(screen);
            this.renderFrame(false);
        }
    }

    private void updateLevelInEngines(final @Nullable ClientLevel level) {
        this.updateLevelInEngines(level, true);
    }

    private void updateLevelInEngines(final @Nullable ClientLevel level, final boolean stopSound) {
        if (stopSound) {
            this.soundManager.stop();
        }

        this.setCameraEntity(null);
        this.pendingConnection = null;
        this.levelExtractor.setLevel(level);
        this.particleEngine.setLevel(level);
        this.gameRenderer.setLevel(level);
        this.updateTitle();
    }

    private UserProperties userProperties() {
        return this.userPropertiesFuture.join();
    }

    public boolean telemetryOptInExtra() {
        return this.extraTelemetryAvailable() && this.options.telemetryOptInExtra().get();
    }

    public boolean extraTelemetryAvailable() {
        return this.allowsTelemetry() && this.userProperties().flag(UserFlag.OPTIONAL_TELEMETRY_AVAILABLE);
    }

    public boolean allowsTelemetry() {
        return SharedConstants.IS_RUNNING_IN_IDE && !SharedConstants.DEBUG_FORCE_TELEMETRY ? false : this.userProperties().flag(UserFlag.TELEMETRY_ENABLED);
    }

    public boolean allowsMultiplayer() {
        return this.allowsMultiplayer && this.userProperties().flag(UserFlag.SERVERS_ALLOWED) && this.multiplayerBan() == null && !this.isNameBanned();
    }

    public boolean allowsRealms() {
        return this.userProperties().flag(UserFlag.REALMS_ALLOWED) && this.multiplayerBan() == null;
    }

    public boolean friendsEnabled() {
        return this.userProperties().flag(UserFlag.FRIENDS_ENABLED);
    }

    public boolean handleGlobalKeyPress(final InputConstants.Key key, final boolean controlDown) {
        if (this.options.keyDebugModifier.isDown()) {
            return false;
        } else if (this.options.keyFullscreen.matches(key)) {
            this.toggleFullscreen();
            return true;
        } else if (this.options.keyScreenshot.matches(key)) {
            Screenshot.grab(this, controlDown);
            return true;
        } else {
            return this.options.keyFriends.matches(key) ? this.toggleFriendsScreen() : false;
        }
    }

    private void toggleFullscreen() {
        Window window = this.getWindow();
        window.toggleFullScreen();
        boolean fullscreen = window.isFullscreen();
        this.options.fullscreen().set(fullscreen);
        this.options.save();
        if (this.gui.screen() instanceof VideoSettingsScreen videoSettingsScreen) {
            videoSettingsScreen.updateFullscreenButton(fullscreen);
        }
    }

    private boolean toggleFriendsScreen() {
        if (this.isDemo()) {
            return false;
        }

        Screen current = this.gui.screen();
        if (current instanceof FriendsOverlayScreen friends) {
            ComponentPath focusPath = current.getCurrentFocusPath();
            if (focusPath == null || !(focusPath.leafComponent() instanceof EditBox)) {
                friends.onClose();
                return true;
            }
        }

        if (current != null && !(current instanceof TitleScreen) && !(current instanceof PauseScreen)) {
            return false;
        }

        OnlineOptionsScreen.confirmFriendsListEnabled(this, () -> this.gui.gui.setScreen(new FriendsOverlayScreen(current)), current);
        return true;
    }

    public boolean allowFriendRequests() {
        return this.userProperties().flag(UserFlag.ACCEPT_FRIEND_INVITES);
    }

    public boolean allowChatOnlyWithFriend() {
        return SharedConstants.DEBUG_CHAT_FRIENDS_ONLY || this.userProperties().flag(UserFlag.CHAT_FRIENDS_ONLY);
    }

    public @Nullable BanDetails multiplayerBan() {
        return this.userProperties().bannedScopes().get("MULTIPLAYER");
    }

    public boolean isNameBanned() {
        ProfileResult result = this.profileFuture.getNow(null);
        return result != null && result.actions().contains(ProfileActionType.FORCED_NAME_CHANGE);
    }

    public boolean isBlocked(final UUID uuid) {
        return !this.isLocalOrUnknownPlayer(uuid) && this.playerSocialManager.shouldHideMessageFrom(uuid);
    }

    public boolean isFriendOnlyRestricted(final UUID uuid) {
        if (!this.allowChatOnlyWithFriend()) {
            return false;
        }

        ClientPacketListener conn = this.getConnection();
        return conn != null && conn.getLocalGameProfile().id().equals(uuid)
            ? false
            : !this.getPlayerSocialManager().isFriendListEnabled() || !this.getPlayerSocialManager().isFriend(uuid);
    }

    private boolean isLocalOrUnknownPlayer(final UUID uuid) {
        return uuid.equals(Util.NIL_UUID) ? true : this.player != null && uuid.equals(this.player.getUUID());
    }

    public ChatAbilities computeChatAbilities() {
        ChatAbilities.Builder builder = new ChatAbilities.Builder();
        ChatVisiblity visiblityOption = this.options.chatVisibility().get();
        if (visiblityOption == ChatVisiblity.HIDDEN) {
            builder.addRestriction(ChatRestriction.CHAT_AND_COMMANDS_DISABLED_BY_OPTIONS);
        } else if (visiblityOption == ChatVisiblity.SYSTEM) {
            builder.addRestriction(ChatRestriction.CHAT_DISABLED_BY_OPTIONS);
        }

        if (this.isMultiplayerServer()) {
            if (!this.allowsChat) {
                builder.addRestriction(ChatRestriction.DISABLED_BY_LAUNCHER);
            }

            if (SharedConstants.DEBUG_CHAT_DISABLED || !this.userProperties().flag(UserFlag.CHAT_ALLOWED)) {
                builder.addRestriction(ChatRestriction.DISABLED_BY_PROFILE);
            }
        }

        return builder.build();
    }

    public final boolean isDemo() {
        return this.demo;
    }

    public final boolean canSwitchGameMode() {
        return this.player != null && this.gameMode != null;
    }

    public @Nullable ClientPacketListener getConnection() {
        return this.player == null ? null : this.player.connection;
    }

    private void pickBlockOrEntity() {
        if (this.hitResult != null && this.hitResult.getType() != HitResult.Type.MISS) {
            if (net.minecraftforge.client.event.ForgeEventFactoryClient.onClickInputPickBlock(this.options.keyPickItem)) return;
            boolean includeData = this.hasControlDown();
            switch (this.hitResult) {
                case BlockHitResult blockHitResult:
                    this.gameMode.handlePickItemFromBlock(blockHitResult.getBlockPos(), includeData);
                    break;
                case EntityHitResult entityHitResult:
                    this.gameMode.handlePickItemFromEntity(entityHitResult.getEntity(), includeData);
                    break;
                default:
            }
        }
    }

    public CrashReport fillReport(final CrashReport report) {
        SystemReport systemReport = report.getSystemReport();

        try {
            fillSystemReport(systemReport, this, this.languageManager, this.launchedVersion, this.options);
            this.fillUptime(report.addCategory("Uptime"));
            if (this.level != null) {
                this.level.fillReportDetails(report);
            }

            if (this.singleplayerServer != null) {
                this.singleplayerServer.fillSystemReport(systemReport);
            }

            this.reloadStateTracker.fillCrashReport(report);
        } catch (Throwable t) {
            LOGGER.error("Failed to collect details", t);
        }

        return report;
    }

    public static void fillReport(
        final @Nullable Minecraft minecraft,
        final @Nullable LanguageManager languageManager,
        final @Nullable String launchedVersion,
        final @Nullable Options options,
        final CrashReport report
    ) {
        SystemReport system = report.getSystemReport();
        fillSystemReport(system, minecraft, languageManager, launchedVersion, options);
    }

    private static String formatSeconds(final double timeInSeconds) {
        return String.format(Locale.ROOT, "%.3fs", timeInSeconds);
    }

    private void fillUptime(final CrashReportCategory category) {
        category.setDetail("JVM uptime", () -> formatSeconds(ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
        category.setDetail("Wall uptime", () -> formatSeconds((System.currentTimeMillis() - this.clientStartTimeMs) / 1000.0));
        category.setDetail("High-res time", () -> formatSeconds(Util.getMillis() / 1000.0));
        category.setDetail("Client ticks", () -> String.format(Locale.ROOT, "%d ticks / %.3fs", this.clientTickCount, this.clientTickCount / 20.0));
    }

    private static SystemReport fillSystemReport(
        final SystemReport systemReport,
        final @Nullable Minecraft minecraft,
        final @Nullable LanguageManager languageManager,
        final @Nullable String launchedVersion,
        final @Nullable Options options
    ) {
        systemReport.setDetail("Launched Version", () -> launchedVersion);
        String launcherBrand = getLauncherBrand();
        if (launcherBrand != null) {
            systemReport.setDetail("Launcher name", launcherBrand);
        }

        systemReport.setDetail("Backend library", RenderSystem::getBackendDescription);
        systemReport.setDetail("Window size", () -> minecraft != null ? minecraft.window.getWidth() + "x" + minecraft.window.getHeight() : "<not initialized>");
        systemReport.setDetail("Surface Info", () -> {
            if (minecraft != null && minecraft.windowSurface != null) {
                StringBuilder builder = new StringBuilder();
                if (minecraft.windowSurface.isAcquired()) {
                    builder.append("acquired, ");
                }

                if (minecraft.windowSurface.isSuboptimal()) {
                    builder.append("suboptimal, ");
                }

                if (minecraft.windowSurface.currentConfiguration().isEmpty()) {
                    builder.append("unconfigured");
                } else {
                    GpuSurface.Configuration config = minecraft.windowSurface.currentConfiguration().get();
                    builder.append(config.presentMode()).append(", ");
                    builder.append(config.width()).append(" x ").append(config.height());
                }

                return builder.toString();
            } else {
                return "<no surface>";
            }
        });
        systemReport.setDetail("GFLW Platform", Window::getPlatform);
        GpuDevice device = RenderSystem.tryGetDevice();
        DeviceInfo deviceInfo = device == null ? null : device.getDeviceInfo();
        if (deviceInfo != null) {
            systemReport.setDetail("Graphics Device", deviceInfo.name());
            systemReport.setDetail("Graphics Backend", deviceInfo.backendName());
            systemReport.setDetail("Graphics Vendor", deviceInfo.vendorName());
            systemReport.setDetail("Graphics Drivers", deviceInfo.driverInfo());
            systemReport.setDetail("Graphics Device Extensions", String.join(", ", deviceInfo.underlyingExtensions()));
            systemReport.setDetail("Graphics Device Type", deviceInfo.type().toString());
            systemReport.setDetail("Graphics Device Features", deviceInfo.features().toString());
        } else {
            systemReport.setDetail("Graphics Device", "<none>");
        }

        if (minecraft != null && minecraft.backendCreationException != null) {
            BackendCreationException exception = minecraft.backendCreationException;
            systemReport.setDetail("Backend failure message", exception.getMessage());
            systemReport.setDetail("Backend failure reason", exception.getReason().displayName());
            systemReport.setDetail("Backend failure missing capabilities", String.join(", ", exception.getMissingCapabilities()));
        }

        systemReport.setDetail("GL debug messages", () -> {
            if (device == null) {
                return "<no renderer available>";
            } else {
                return device.isDebuggingEnabled() ? String.join("\n", device.getLastDebugMessages()) : "<debugging unavailable>";
            }
        });
        systemReport.setDetail("Is Modded", () -> checkModStatus().fullDescription());
        systemReport.setDetail("Universe", () -> minecraft != null ? Long.toHexString(minecraft.canary) : "404");
        systemReport.setDetail("Type", "Client");
        if (options != null) {
            if (minecraft != null) {
                String gpuWarnings = minecraft.getGpuWarnlistManager().getAllWarnings();
                if (gpuWarnings != null) {
                    systemReport.setDetail("GPU Warnings", gpuWarnings);
                }
            }

            systemReport.setDetail("Transparency", options.improvedTransparency().get() ? "shader" : "regular");
            systemReport.setDetail("Render Distance", options.getEffectiveRenderDistance() + "/" + options.renderDistance().get() + " chunks");
            systemReport.setDetail("Narrator config", options.narrator().get().toString());
        }

        if (minecraft != null) {
            systemReport.setDetail("Narrator status", () -> minecraft.narrator.isActive());
            systemReport.setDetail("Resource Packs", () -> PackRepository.displayPackList(minecraft.getResourcePackRepository().getSelectedPacks()));
            systemReport.setDetail("Sound Cache", () -> {
                SoundBufferLibrary.DebugOutput.Counter counter = new SoundBufferLibrary.DebugOutput.Counter();
                minecraft.getSoundManager().getSoundCacheDebugStats(counter);
                return String.format(Locale.ROOT, "%d bytes in %d buffers", counter.totalSize(), counter.totalCount());
            });
        }

        if (languageManager != null) {
            systemReport.setDetail("Current Language", () -> languageManager.getSelected());
        }

        systemReport.setDetail("Locale", String.valueOf(Locale.getDefault()));
        systemReport.setDetail("System encoding", () -> System.getProperty("sun.jnu.encoding", "<not set>"));
        systemReport.setDetail("File encoding", () -> System.getProperty("file.encoding", "<not set>"));
        systemReport.setDetail("CPU", GLX::_getCpuInfo);
        return systemReport;
    }

    public static Minecraft getInstance() {
        return instance;
    }

    public CompletableFuture<Void> delayTextureReload() {
        return this.<CompletableFuture<Void>>submit((java.util.function.Supplier<CompletableFuture<Void>>)this::reloadResourcePacks).thenCompose(result -> (CompletionStage<Void>)result);
    }

    public void updateReportEnvironment(final ReportEnvironment environment) {
        if (!this.reportingContext.matches(environment)) {
            this.reportingContext = ReportingContext.create(environment, this.userApiService);
        }
    }

    public @Nullable ServerData getCurrentServer() {
        return Optionull.map(this.getConnection(), ClientPacketListener::getServerData);
    }

    public boolean isLocalServer() {
        return this.isLocalServer;
    }

    public boolean hasSingleplayerServer() {
        return this.isLocalServer && this.singleplayerServer != null;
    }

    public @Nullable IntegratedServer getSingleplayerServer() {
        return this.singleplayerServer;
    }

    public boolean isLocalPlayer(final UUID profileId) {
        return profileId.equals(this.getUser().getProfileId());
    }

    public User getUser() {
        return this.user;
    }

    public @Nullable ProfileResult getProfileResult() {
        return this.profileFuture.join();
    }

    public GameProfile getGameProfile() {
        ProfileResult profileResult = this.profileFuture.join();
        return profileResult != null ? profileResult.profile() : new GameProfile(this.user.getProfileId(), this.user.getName());
    }

    public Proxy getProxy() {
        return this.proxy;
    }

    public TextureManager getTextureManager() {
        return this.textureManager;
    }

    public ShaderManager getShaderManager() {
        return this.shaderManager;
    }

    public ResourceManager getResourceManager() {
        return this.resourceManager;
    }

    public PackRepository getResourcePackRepository() {
        return this.resourcePackRepository;
    }

    public VanillaPackResources getVanillaPackResources() {
        return this.vanillaPackResources;
    }

    public DownloadedPackSource getDownloadedPackSource() {
        return this.downloadedPackSource;
    }

    public Path getResourcePackDirectory() {
        return this.resourcePackDirectory;
    }

    public LanguageManager getLanguageManager() {
        return this.languageManager;
    }

    public boolean isPaused() {
        return this.pause;
    }

    public GpuWarnlistManager getGpuWarnlistManager() {
        return this.gpuWarnlistManager;
    }

    public SoundManager getSoundManager() {
        return this.soundManager;
    }

    public @Nullable Music getSituationalMusic() {
        Music screenMusic = Optionull.map(this.gui.screen(), Screen::getBackgroundMusic);
        if (screenMusic != null) {
            return screenMusic;
        }

        Camera camera = this.gameRenderer.mainCamera();
        if (this.player != null) {
            Level playerLevel = this.player.level();
            if (playerLevel.dimension() == Level.END && this.gui.hud.getBossOverlay().shouldPlayMusic()) {
                return Musics.END_BOSS;
            }

            BackgroundMusic backgroundMusic = camera.attributeProbe().getValue(EnvironmentAttributes.BACKGROUND_MUSIC, 1.0F);
            boolean isCreative = this.player.getAbilities().instabuild && this.player.getAbilities().mayfly;
            boolean isUnderwater = this.player.isUnderWater();
            return backgroundMusic.select(isCreative, isUnderwater).orElse(null);
        } else {
            return Musics.MENU;
        }
    }

    public float getMusicVolume() {
        return this.gui.screen() != null && this.gui.screen().getBackgroundMusic() != null
            ? 1.0F
            : this.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.MUSIC_VOLUME, 1.0F);
    }

    public Services services() {
        return this.services;
    }

    public SkinManager getSkinManager() {
        return this.skinManager;
    }

    public @Nullable Entity getCameraEntity() {
        return this.gameRenderer.mainCamera().entity();
    }

    public void setCameraEntity(final @Nullable Entity cameraEntity) {
        this.gameRenderer.mainCamera().setEntity(cameraEntity);
        this.gameRenderer.checkEntityPostEffect(cameraEntity);
    }

    public boolean shouldEntityAppearGlowing(final Entity entity) {
        return entity.isCurrentlyGlowing()
            || this.player != null && this.player.isSpectator() && this.options.keySpectatorOutlines.isDown() && entity.is(EntityTypes.PLAYER);
    }

    @Override
    public Thread getRunningThread() {
        return this.gameThread;
    }

    @Override
    public Runnable wrapRunnable(final Runnable runnable) {
        return runnable;
    }

    @Override
    protected boolean shouldRun(final Runnable task) {
        return true;
    }

    public EntityRenderDispatcher getEntityRenderDispatcher() {
        return this.entityRenderDispatcher;
    }

    public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
        return this.blockEntityRenderDispatcher;
    }

    public MapRenderer getMapRenderer() {
        return this.mapRenderer;
    }

    public DataFixer getFixerUpper() {
        return this.fixerUpper;
    }

    public DeltaTracker getDeltaTracker() {
        return this.deltaTracker;
    }

    public BlockColors getBlockColors() {
        return this.blockColors;
    }

    public boolean showOnlyReducedInfo() {
        return this.player != null && this.player.isReducedDebugInfo() || this.options.reducedDebugInfo().get();
    }

    public Tutorial getTutorial() {
        return this.tutorial;
    }

    public boolean isWindowActive() {
        return this.window.isFocused();
    }

    public HotbarManager getHotbarManager() {
        return this.hotbarManager;
    }

    public ModelManager getModelManager() {
        return this.modelManager;
    }

    public AtlasManager getAtlasManager() {
        return this.atlasManager;
    }

    public MapTextureManager getMapTextureManager() {
        return this.mapTextureManager;
    }

    public Component grabPanoramixScreenshot(final File folder) {
        int downscaleFactor = 4;
        int width = 4096;
        int height = 4096;
        int ow = this.window.getWidth();
        int oh = this.window.getHeight();
        RenderTarget target = this.gameRenderer.mainRenderTarget();
        float xRot = this.player.getXRot();
        float yRot = this.player.getYRot();
        float xRotO = this.player.xRotO;
        float yRotO = this.player.yRotO;
        this.gameRenderer.setRenderBlockOutline(false);
        Camera camera = this.gameRenderer.mainCamera();

        try {
            camera.enablePanoramicMode();
            this.window.setWidth(4096);
            this.window.setHeight(4096);
            target.resize(4096, 4096);

            for (int i = 0; i < 6; i++) {
                switch (i) {
                    case 0:
                        this.player.setYRot(yRot);
                        this.player.setXRot(0.0F);
                        break;
                    case 1:
                        this.player.setYRot((yRot + 90.0F) % 360.0F);
                        this.player.setXRot(0.0F);
                        break;
                    case 2:
                        this.player.setYRot((yRot + 180.0F) % 360.0F);
                        this.player.setXRot(0.0F);
                        break;
                    case 3:
                        this.player.setYRot((yRot - 90.0F) % 360.0F);
                        this.player.setXRot(0.0F);
                        break;
                    case 4:
                        this.player.setYRot(yRot);
                        this.player.setXRot(-90.0F);
                        break;
                    case 5:
                    default:
                        this.player.setYRot(yRot);
                        this.player.setXRot(90.0F);
                }

                this.player.yRotO = this.player.getYRot();
                this.player.xRotO = this.player.getXRot();
                this.gameRenderer.update(DeltaTracker.ONE);
                this.gameRenderer.extract(DeltaTracker.ONE, true);
                this.gameRenderer.renderLevel(DeltaTracker.ONE);

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException var19) {
                }

                Screenshot.grab(folder, "panorama_" + i + ".png", target, 4, var0 -> {});
            }

            Component name = Component.literal(folder.getName())
                .withStyle(ChatFormatting.UNDERLINE)
                .withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(folder.getAbsoluteFile())));
            return Component.translatable("screenshot.success", name);
        } catch (Exception e) {
            LOGGER.error("Couldn't save image", e);
            return Component.translatable("screenshot.failure", e.getMessage());
        } finally {
            this.player.setXRot(xRot);
            this.player.setYRot(yRot);
            this.player.xRotO = xRotO;
            this.player.yRotO = yRotO;
            this.gameRenderer.setRenderBlockOutline(true);
            this.window.setWidth(ow);
            this.window.setHeight(oh);
            target.resize(ow, oh);
            camera.disablePanoramicMode();
        }
    }

    public PlayerSocialManager getPlayerSocialManager() {
        return this.playerSocialManager;
    }

    public Window getWindow() {
        return this.window;
    }

    public TextInputManager textInputManager() {
        return this.textInputManager;
    }

    public void onTextInputFocusChange(final GuiEventListener element, final boolean isFocused) {
        this.textInputManager.onTextInputFocusChange(isFocused);
        if (this.gui.screen() != null) {
            if (isFocused) {
                this.keyboardHandler.resubmitLastPreeditEvent(element);
            } else {
                KeyboardHandler.submitPreeditEvent(element, null);
            }
        }
    }

    public GpuSurface windowSurface() {
        return this.windowSurface;
    }

    public FramerateLimitTracker getFramerateLimitTracker() {
        return this.framerateLimitTracker;
    }

    public DebugScreenOverlay getDebugOverlay() {
        return this.gui.hud.getDebugOverlay();
    }

    public void updateMaxMipLevel(final int mipmapLevels) {
        this.atlasManager.updateMaxMipLevel(mipmapLevels);
    }

    public EntityModelSet getEntityModels() {
        return this.modelManager.entityModels().get();
    }

    public boolean isTextFilteringEnabled() {
        return this.userProperties().flag(UserFlag.PROFANITY_FILTER_ENABLED);
    }

    public void prepareForMultiplayer() {
        this.playerSocialManager.startOnlineMode();
        this.getProfileKeyPairManager().prepareKeyPair();
    }

    public InputType getLastInputType() {
        return this.lastInputType;
    }

    public void setLastInputType(final InputType lastInputType) {
        this.lastInputType = lastInputType;
    }

    public GameNarrator getNarrator() {
        return this.narrator;
    }

    public ReportingContext getReportingContext() {
        return this.reportingContext;
    }

    public RealmsDataFetcher realmsDataFetcher() {
        return this.realmsDataFetcher;
    }

    public QuickPlayLog quickPlayLog() {
        return this.quickPlayLog;
    }

    public DirectoryValidator directoryValidator() {
        return this.directoryValidator;
    }

    public PlayerSkinRenderCache playerSkinRenderCache() {
        return this.playerSkinRenderCache;
    }

    private float getTickTargetMillis(final float defaultTickTargetMillis) {
        if (this.level != null) {
            TickRateManager manager = this.level.tickRateManager();
            if (manager.runsNormally()) {
                return Math.max(defaultTickTargetMillis, manager.millisecondsPerTick());
            }
        }

        return defaultTickTargetMillis;
    }

    public ItemModelResolver getItemModelResolver() {
        return this.itemModelResolver;
    }

    public boolean canInterruptScreen() {
        return this.gui.canInterruptScreen();
    }

    public void invalidateSurfaceConfiguration() {
        this.windowSurfaceNeedsReconfiguring = true;
    }

    public static @Nullable String getLauncherBrand() {
        return System.getProperty("minecraft.launcher.brand");
    }

    public PacketProcessor packetProcessor() {
        return this.packetProcessor;
    }

    public Gizmos.TemporaryCollection collectPerTickGizmos() {
        return Gizmos.withCollector(this.perTickGizmos);
    }

    public Collection<SimpleGizmoCollector.GizmoInstance> getPerTickGizmos() {
        return this.drainedLatestTickGizmos;
    }

    private void pick(final float partialTicks) {
        Entity cameraEntity = this.getCameraEntity();
        if (cameraEntity != null) {
            if (this.level != null && this.player != null) {
                Profiler.get().push("pick");
                this.hitResult = this.player.raycastHitResult(partialTicks, cameraEntity);
                this.crosshairPickEntity = this.hitResult instanceof EntityHitResult entityHitResult ? entityHitResult.getEntity() : null;
                Profiler.get().pop();
            }
        }
    }

    public void showDebugChat(final Component message) {
        this.gui.hud.getChat().addClientSystemMessage(message);
        this.getNarrator().saySystemQueued(message);
    }
}
