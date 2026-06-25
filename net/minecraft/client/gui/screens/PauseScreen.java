package net.minecraft.client.gui.screens;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommonButtons;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Dialogs;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DialogTags;
import net.minecraft.util.CommonLinks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class PauseScreen extends Screen {
    private static final Identifier DRAFT_REPORT_SPRITE = Identifier.withDefaultNamespace("icon/draft_report");
    private static final int COLUMNS = 2;
    private static final int MENU_PADDING_TOP = 50;
    private static final int BUTTON_PADDING = 4;
    private static final int BUTTON_WIDTH_FULL = 204;
    private static final int BUTTON_WIDTH_HALF = 98;
    private static final Component RETURN_TO_GAME = Component.translatable("menu.returnToGame");
    private static final Component ADVANCEMENTS = Component.translatable("gui.advancements");
    private static final Component STATS = Component.translatable("gui.stats");
    private static final Component SEND_FEEDBACK = Component.translatable("menu.sendFeedback");
    private static final Component REPORT_BUGS = Component.translatable("menu.reportBugs");
    private static final Component OPTIONS = Component.translatable("menu.options");
    private static final Component MULTIPLAYER_OPTIONS = Component.translatable("menu.multiplayerOptions.button");
    private static final Component PLAYER_REPORTING = Component.translatable("menu.playerReporting");
    private static final Component GAME = Component.translatable("menu.game");
    private static final Component PAUSED = Component.translatable("menu.paused");
    private static final Tooltip CUSTOM_OPTIONS_TOOLTIP = Tooltip.create(Component.translatable("menu.custom_options.tooltip"));
    private static final Tooltip NO_PLAYERS_TO_REPORT_TOOLTIP = Tooltip.create(Component.translatable("menu.playerReporting.no_players"));
    private final Runnable friendListUpdateListener = this::onFriendListUpdate;
    private @Nullable FriendsButton friends;
    private final boolean showPauseMenu;
    private @Nullable Button disconnectButton;

    public PauseScreen(final boolean showPauseMenu) {
        super(showPauseMenu ? GAME : PAUSED);
        this.showPauseMenu = showPauseMenu;
    }

    public boolean showsPauseMenu() {
        return this.showPauseMenu;
    }

    @Override
    protected void init() {
        if (this.showPauseMenu) {
            this.createPauseMenu();
        }

        int textWidth = this.font.width(this.title);
        this.addRenderableWidget(new StringWidget(this.width / 2 - textWidth / 2, this.showPauseMenu ? 40 : 10, textWidth, 9, this.title, this.font));
    }

    private void createPauseMenu() {
        GridLayout gridLayout = new GridLayout();
        gridLayout.defaultCellSetting().padding(4, 4, 4, 0);
        GridLayout.RowHelper helper = gridLayout.createRowHelper(2);
        helper.addChild(Button.builder(RETURN_TO_GAME, button -> {
            this.minecraft.gui.setScreen(null);
            this.minecraft.mouseHandler.grabMouse();
        }).width(204).build(), 2, gridLayout.newCellSettings().paddingTop(50));
        helper.addChild(this.openScreenButton(ADVANCEMENTS, () -> new AdvancementsScreen(this.minecraft.player.connection.getAdvancements(), this)));
        helper.addChild(this.openScreenButton(STATS, () -> new StatsScreen(this, this.minecraft.player.getStats())));
        LinearLayout iconButtonRow = LinearLayout.horizontal().spacing(4);
        SpriteIconButton reportBugsButton = SpriteIconButton.builder(REPORT_BUGS, ConfirmLinkScreen.confirmLink(this, CommonLinks.SNAPSHOT_BUGS_FEEDBACK), true)
            .width(20)
            .sprite(Identifier.withDefaultNamespace("pause_menu/bug"), 15, 15)
            .withTootip()
            .build();
        reportBugsButton.active = !SharedConstants.getCurrentVersion().dataVersion().isSideSeries();
        iconButtonRow.addChild(reportBugsButton);
        SpriteIconButton feedbackButton = SpriteIconButton.builder(
                SEND_FEEDBACK,
                ConfirmLinkScreen.confirmLink(this, SharedConstants.getCurrentVersion().stable() ? CommonLinks.RELEASE_FEEDBACK : CommonLinks.SNAPSHOT_FEEDBACK),
                true
            )
            .width(20)
            .sprite(Identifier.withDefaultNamespace("pause_menu/social_interactions"), 15, 15)
            .withTootip()
            .build();
        iconButtonRow.addChild(feedbackButton);
        PlayerSocialManager playerSocialManager = this.minecraft.getPlayerSocialManager();
        if (playerSocialManager.isFriendListEnabled()) {
            playerSocialManager.addFriendListUpdateListener(this.friendListUpdateListener);
        }

        this.friends = CommonButtons.friends(
            20,
            var1x -> OnlineOptionsScreen.confirmFriendsListEnabled(this.minecraft, () -> this.minecraft.gui.setScreen(new FriendsOverlayScreen(this)), this),
            !this.minecraft.isDemo()
        );
        iconButtonRow.addChild(this.friends);
        SpriteIconButton playerReportingButton = SpriteIconButton.builder(
                PLAYER_REPORTING, var1x -> this.minecraft.gui.setScreen(new SocialInteractionsScreen(this)), true
            )
            .width(20)
            .sprite(Identifier.withDefaultNamespace("pause_menu/player_reporting"), 15, 15)
            .withTootip()
            .build();
        iconButtonRow.addChild(playerReportingButton);
        IntegratedServer integratedServer = this.minecraft.getSingleplayerServer();
        if (integratedServer != null && this.minecraft.player != null) {
            List<Entry<UUID, PlayerInfo>> list = this.minecraft
                .player
                .connection
                .getSeenPlayers()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey() != this.minecraft.player.getUUID())
                .toList();
            if (list.isEmpty()) {
                playerReportingButton.active = false;
                playerReportingButton.setTooltip(NO_PLAYERS_TO_REPORT_TOOLTIP);
            }
        }

        helper.addChild(iconButtonRow, 2, gridLayout.newCellSettings().alignHorizontallyCenter());
        Optional<? extends Holder<Dialog>> additions = this.getCustomAdditions();
        additions.ifPresent(dialogHolder -> this.addCustomDialogButtons(this.minecraft, (Holder<Dialog>)dialogHolder, helper));
        if (this.minecraft.hasSingleplayerServer()) {
            helper.addChild(this.openScreenButton(OPTIONS, () -> new OptionsScreen(this, this.minecraft.options, true)));
            helper.addChild(this.openScreenButton(MULTIPLAYER_OPTIONS, () -> new MultiplayerOptionsScreen(this)));
        } else {
            helper.addChild(
                Button.builder(OPTIONS, var1x -> this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options, true))).width(204).build(), 2
            );
        }
        helper.addChild(Button.builder(Component.translatable("fml.menu.mods"), button -> this.minecraft.gui.setScreen(new net.minecraftforge.client.gui.ModListScreen(this))).width(BUTTON_WIDTH_FULL).build(), 2);

        this.disconnectButton = helper.addChild(
            Button.builder(
                    CommonComponents.disconnectButtonLabel(this.minecraft.isLocalServer()),
                    button -> {
                        button.active = false;
                        this.minecraft
                            .getReportingContext()
                            .draftReportHandled(this.minecraft, this, () -> this.minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), true);
                    }
                )
                .width(204)
                .build(),
            2
        );
        gridLayout.arrangeElements();
        FrameLayout.alignInRectangle(gridLayout, 0, 0, this.width, this.height, 0.5F, 0.25F);
        gridLayout.visitWidgets(this::addRenderableWidget);
    }

    private Optional<? extends Holder<Dialog>> getCustomAdditions() {
        Registry<Dialog> dialogRegistry = this.minecraft.player.connection.registryAccess().lookupOrThrow(Registries.DIALOG);
        Optional<? extends HolderSet<Dialog>> maybeCustomAdditions = dialogRegistry.get(DialogTags.PAUSE_SCREEN_ADDITIONS);
        if (maybeCustomAdditions.isPresent()) {
            HolderSet<Dialog> customAdditions = (HolderSet<Dialog>)maybeCustomAdditions.get();
            if (customAdditions.size() > 0) {
                if (customAdditions.size() == 1) {
                    return Optional.of(customAdditions.get(0));
                }

                return dialogRegistry.get(Dialogs.CUSTOM_OPTIONS);
            }
        }

        ServerLinks serverLinks = this.minecraft.player.connection.serverLinks();
        return !serverLinks.isEmpty() ? dialogRegistry.get(Dialogs.SERVER_LINKS) : Optional.empty();
    }

    private void addCustomDialogButtons(final Minecraft minecraft, final Holder<Dialog> dialog, final GridLayout.RowHelper helper) {
        helper.addChild(
            Button.builder(dialog.value().common().computeExternalTitle(), button -> minecraft.player.connection.showDialog(dialog, this))
                .width(204)
                .tooltip(CUSTOM_OPTIONS_TOOLTIP)
                .build(),
            2
        );
    }

    @Override
    public void tick() {
        if (this.rendersNowPlayingToast()) {
            NowPlayingToast.tickMusicNotes();
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (this.rendersNowPlayingToast()) {
            NowPlayingToast.extractToast(graphics, this.font);
        }

        if (this.showPauseMenu && this.minecraft.getReportingContext().hasDraftReport() && this.disconnectButton != null) {
            graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                DRAFT_REPORT_SPRITE,
                this.disconnectButton.getX() + this.disconnectButton.getWidth() - 17,
                this.disconnectButton.getY() + 3,
                15,
                15
            );
        }
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        if (this.showPauseMenu) {
            if (this.isTopmostScreen()) {
                this.extractBlurredBackground(graphics);
            }

            this.extractMenuBackground(graphics);
            this.minecraft.gui.hud.extractDeferredSubtitles();
        }
    }

    @Override
    public void onClose() {
        super.onClose();
        this.minecraft.getPlayerSocialManager().removeFriendListUpdateListener(this.friendListUpdateListener);
    }

    public boolean rendersNowPlayingToast() {
        Options options = this.minecraft.options;
        return options.musicToast().get().renderInPauseScreen() && options.getFinalSoundSourceVolume(SoundSource.MUSIC) > 0.0F && this.showPauseMenu;
    }

    private boolean isTopmostScreen() {
        return this.minecraft.gui.screen() == this;
    }

    private Button openScreenButton(final Component message, final Supplier<Screen> newScreen) {
        return Button.builder(message, var2 -> this.minecraft.gui.setScreen(newScreen.get())).width(98).build();
    }

    private void onFriendListUpdate() {
        if (this.friends != null) {
            this.friends.refreshIncomingRequestCount();
        }
    }
}
