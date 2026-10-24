package squeek.appleskin.client;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import squeek.appleskin.ModConfig;
import squeek.appleskin.helpers.FoodHelper;
import squeek.appleskin.helpers.KeyHelper;

/** A real-client regression for both held-out AppleSkin ports; no desktop capture or image golden. */
public final class PortClientTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.getGuiScale().setValue(2);
			client.getWindow().setWindowedSize(1280, 720);
		});
		context.waitTicks(2);
		try (var world = context.worldBuilder().create()) {
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("give @a minecraft:apple 1");
			context.waitTicks(10);
			context.runOnClient(client -> {
				KeyHelper.isCtrlKeyDown(); KeyHelper.isShiftKeyDown();
				client.player.setHealth(14f);
				FoodHelper.getEstimatedHealthIncrement(client.player, FoodHelper.getDefaultFoodValues(Items.APPLE.getDefaultStack()));
				ModConfig.INSTANCE.showFoodValuesInTooltipAlways = true;
				ModConfig.INSTANCE.showFoodDebugInfo = true;
			});
			context.getInput().pressKey(GLFW.GLFW_KEY_F3);
			context.waitTicks(3);
			context.runOnClient(client -> {
				var lines = new java.util.ArrayList<String>();
				DebugInfoHandler.INSTANCE.onTextRender(lines);
				if (lines.stream().noneMatch(line -> line.startsWith("hunger: "))) { throw new AssertionError("food debug info missing"); }
				try {
					var profile = client.getClass().getField("debugHudEntryList").get(client);
					var visible = (java.util.Collection<?>) profile.getClass().getMethod("getVisibleEntries").invoke(profile);
					if (visible.stream().noneMatch(id -> id.toString().equals("appleskin:food"))) { throw new AssertionError("food debug entry is hidden"); }
				} catch (NoSuchFieldException oldDebugHud) {
					// 1.21.6 uses the original getLeftText mixin, visible in the captured F3 HUD.
				} catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
			});
			shot(context, "appleskin-hud");
			context.getInput().pressKey(GLFW.GLFW_KEY_F3);
			AtomicInteger drawn = new AtomicInteger();
			context.setScreen(() -> new Screen(Text.literal("AppleSkin port verification")) {
				@Override public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
					draw.fill(0, 0, width, height, 0xFF20252B);
					draw.drawTextWithShadow(textRenderer, "AppleSkin port: ordinary tooltip and large-value counters", 24, 24, 0xFFFFFFFF);
					draw.drawItemTooltip(textRenderer, Items.APPLE.getDefaultStack(), 60, 80);
					var food = new FoodComponent.Builder().nutrition(40).saturationModifier(1f).build();
					var overlay = new TooltipOverlayHandler.FoodOverlay(Items.APPLE.getDefaultStack(), food, food,
							FoodHelper.DEFAULT_CONSUMABLE_COMPONENT, client.player);
					overlay.drawItems(textRenderer, 240, 90, 100, 40, draw);
					drawn.incrementAndGet();
				}
				@Override public boolean shouldPause() { return false; }
			});
			context.waitTicks(3);
			shot(context, "appleskin-tooltips");
			if (drawn.get() == 0) { throw new AssertionError("tooltip renderer did not execute"); }
			context.setScreen(() -> null);
		}
	}
	private static void shot(ClientGameTestContext context, String name) {
		Path path = context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 720).disableCounterPrefix());
		try {
			var png = ImageIO.read(path.toFile());
			if (png == null || png.getWidth() != 1280 || png.getHeight() != 720) { throw new AssertionError("wrong screenshot size: " + path); }
			System.out.println("PORT_SCREENSHOT 1280x720 " + path.toAbsolutePath());
		} catch (java.io.IOException e) { throw new RuntimeException(e); }
	}
}
