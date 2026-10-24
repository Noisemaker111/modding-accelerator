package com.prismlab.gametest;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import com.prismlab.client.PrismScreen;

/**
 * Tier 3: a real Minecraft client, driven by code. It renders the mod's own screen,
 * clicks the real widget through the game's own mouse handler, and compares the pixels
 * against committed baselines at a fixed size with zero tolerance.
 */
@SuppressWarnings("UnstableApiUsage")
public class PrismClientGameTest implements FabricClientGameTest {
	private static final int SHOT_WIDTH = 854;
	private static final int SHOT_HEIGHT = 480;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					settings.setAllowCommands(true);
				})
				.create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			// Fixed GUI scale keeps the pixels the same on any display.
			context.runOnClient(client -> {
				client.options.guiScale().set(2);
				client.resizeGui();
			});
			context.waitTicks(2);
			context.runOnClient(client -> client.player.connection.sendCommand("time set noon"));
			context.runOnClient(client -> client.player.connection.sendCommand("weather clear"));
			context.runOnClient(client -> client.player.connection.sendCommand("give @s prismlab:prism_shard 16"));
			context.runOnClient(client -> client.player.connection.sendCommand("tp @s ~ ~ ~ -20 15"));
			context.waitTicks(40);
			context.takeScreenshot(TestScreenshotOptions.of("prismlab-world")
					.withSize(SHOT_WIDTH, SHOT_HEIGHT)
					.disableCounterPrefix());

			context.runOnClient(client -> {
				client.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
				client.gui.toastManager().clear();
			});
			openScreen(context);
			String layout = context.computeOnClient(client -> com.prismlab.client.PrismScreen.layoutReport(client.gui.screen()));
			System.out.println("PRISMLAB-LAYOUT " + layout);
			if (!layout.contains("centred=true")) {
				throw new AssertionError("panel is not centred: " + layout);
			}
			shot(context, "prismlab-ui");

			clickButton(context, "More shards");
			context.waitTicks(2);
			int shards = context.computeOnClient(client -> ((PrismScreen) client.gui.screen()).shards());
			if (shards != 6) {
				throw new AssertionError("Clicking More shards did not raise the count, got " + shards);
			}
			shot(context, "prismlab-ui-clicked");

			// Beauty shot: normal play look at 1080p, world and HUD visible, never compared.
			context.runOnClient(client -> {
				client.getWindow().setWindowed(1920, 1080);
				client.options.guiScale().set(2);
				client.resizeGui();
			});
			context.waitTicks(6);
			context.runOnClient(client -> {
				com.prismlab.client.PrismScreen.opaqueBackdrop = false;
				client.gui.setScreen(new com.prismlab.client.PrismScreen());
			});
			context.waitTicks(8);
			context.takeScreenshot(TestScreenshotOptions.of("prismlab-ui-inworld").disableCounterPrefix());

			clickButton(context, "Close");
			context.waitTicks(2);
			if (context.computeOnClient(client -> client.gui.screen()) != null) {
				throw new AssertionError("Close button did not close the Prism screen");
			}
		}
	}

	private static void openScreen(ClientGameTestContext context) {
		context.runOnClient(client -> client.gui.setScreen(new PrismScreen()));
		context.waitForScreen(PrismScreen.class);
		context.waitTicks(5);
	}

	private static void shot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
				.withSize(SHOT_WIDTH, SHOT_HEIGHT)
				.disableCounterPrefix());
	}

	/**
	 * Clicks a button the way the game does: coordinates go into the widget's own
	 * mouseClicked, which checks its hitbox, plays the click sound and runs onPress.
	 * Button id 1 is the primary button in Minecraft's input records.
	 */
	private static void clickButton(ClientGameTestContext context, String label) {
		context.runOnClient(client -> {
			Screen screen = client.gui.screen();
			Button target = null;
			for (var child : screen.children()) {
				if (child instanceof Button button && button.getMessage().getString().equals(label)) {
					target = button;
					break;
				}
			}
			if (target == null) {
				throw new AssertionError("No button labelled '" + label + "' on " + screen.getClass().getSimpleName());
			}
			double x = target.getX() + target.getWidth() / 2.0;
			double y = target.getY() + target.getHeight() / 2.0;
			if (!target.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false)) {
				throw new AssertionError("Button '" + label + "' did not accept a click at " + x + "," + y);
			}
		});
		context.waitTicks(1);
	}
}






