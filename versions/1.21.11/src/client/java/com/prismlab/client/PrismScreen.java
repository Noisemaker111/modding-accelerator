package com.prismlab.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.prismlab.PrismBanner;
import com.prismlab.PrismMath;

/**
 * A real in-game screen, drawn by the mod with the same renderer the player sees.
 * 1.21.11 draws with {@code GuiGraphics#render} / {@code renderBackground} instead of
 * the 26.x {@code GuiGraphicsExtractor#extractRenderState}.
 */
public class PrismScreen extends Screen {
	public static final int PANEL_WIDTH = 220;
	public static final int PANEL_HEIGHT = 172;
	private static final int BUTTON_WIDTH = 92;
	private static final int BUTTON_HEIGHT = 20;
	private static final String HINT = "Click More shards.";

	/** Off for the beauty shot so the world and HUD show through, as in normal play. */
	public static boolean opaqueBackdrop = true;

	private int shards = 5;

	public PrismScreen() {
		super(Component.literal("Prism Lab"));
	}

	public static int panelLeft(int screenWidth) {
		return (screenWidth - PANEL_WIDTH) / 2;
	}

	public static int panelTop(int screenHeight) {
		return (screenHeight - PANEL_HEIGHT) / 2;
	}

	@Override
	protected void init() {
		int left = panelLeft(this.width);
		int top = panelTop(this.height);
		int buttonY = top + PANEL_HEIGHT - BUTTON_HEIGHT - 10;
		this.addRenderableWidget(Button.builder(Component.literal("More shards"), button -> {
			this.shards = Math.min(15, this.shards + 1);
		}).bounds(left + 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
				.bounds(left + PANEL_WIDTH - BUTTON_WIDTH - 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		if (opaqueBackdrop) {
			// A fixed background keeps the screenshot identical on every run and machine.
			graphics.fill(0, 0, this.width, this.height, 0xFF0B1018);
		}
		else {
			// Normal play: the world shows through, dimmed like a vanilla screen.
			graphics.fill(0, 0, this.width, this.height, 0x66000000);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int x = panelLeft(this.width);
		int y = panelTop(this.height);

		graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF141C2B);
		// outline
		graphics.fill(x, y, x + PANEL_WIDTH, y + 1, 0xFF56D6E8);
		graphics.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF56D6E8);
		graphics.fill(x, y, x + 1, y + PANEL_HEIGHT, 0xFF56D6E8);
		graphics.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF56D6E8);
		graphics.drawCenteredString(this.font, this.title, x + PANEL_WIDTH / 2, y + 10, 0xFFFFFFFF);

		// 26.x blits with float uv bounds; 1.21.11 wants integer sprite coordinates, so the
		// shard icon is a plain swatch here instead of a texture blit.
		graphics.fill(x + 12, y + 30, x + 28, y + 46, 0xFF56D6E8);
		graphics.drawString(this.font, "Prism Shard x" + this.shards, x + 36, y + 32, 0xFFE0F7FF, false);
		graphics.drawString(this.font, "Light level: " + PrismMath.lightLevel(this.shards), x + 36, y + 46, 0xFFB8E6F2, false);
		graphics.drawString(this.font, "Full stacks: " + PrismMath.fullStacks(this.shards), x + 36, y + 60, 0xFFB8E6F2, false);
		graphics.drawString(this.font, "Left over: " + PrismMath.remainder(this.shards, 1), x + 36, y + 74, 0xFFB8E6F2, false);

		graphics.drawString(this.font, HINT, x + 12, y + 96, 0xFF7F93A8, false);
		// Comes from a main-source class, so this line shows whether a main-class redefinition
		// landed (see PrismBanner).
		graphics.drawString(this.font, PrismBanner.label(), x + 12, y + 110, 0xFF56D6E8, false);
		// Widgets last, otherwise the panel paints over the buttons.
		super.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	public int shards() {
		return this.shards;
	}

	/** Used by tests to prove the layout fits and stays centred. */
	public static String layoutReport(Screen screen) {
		int x = panelLeft(screen.width);
		int y = panelTop(screen.height);
		return "screen=" + screen.width + "x" + screen.height
				+ " panel=" + x + "," + y + " " + PANEL_WIDTH + "x" + PANEL_HEIGHT
				+ " centred=" + (Math.abs(x + PANEL_WIDTH / 2 - screen.width / 2) <= 1);
	}
}
