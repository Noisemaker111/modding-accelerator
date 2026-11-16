package ${package};

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RenderLevelStageEvent, posted on the game bus at each Stage of
 * drawing the level, from the bridge's LevelStageMixin at the places NeoForge's LevelRenderer and GameRenderer patches
 * post it (the block stages after the chunk layer of their render type). As NeoForge, a stage given no pose stack gets
 * a fresh one.
 */
public class RenderLevelStageEvent extends Event {

    private final Stage stage;
    private final LevelRenderer levelRenderer;
    private final PoseStack poseStack;
    private final Matrix4f modelViewMatrix;
    private final Matrix4f projectionMatrix;
    private final int renderTick;
    private final DeltaTracker partialTick;
    private final Camera camera;
    private final Frustum frustum;

    public RenderLevelStageEvent(Stage stage, LevelRenderer levelRenderer, PoseStack poseStack, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, int renderTick, DeltaTracker partialTick, Camera camera, Frustum frustum) {
        this.stage = stage;
        this.levelRenderer = levelRenderer;
        this.poseStack = poseStack != null ? poseStack : new PoseStack();
        this.modelViewMatrix = modelViewMatrix;
        this.projectionMatrix = projectionMatrix;
        this.renderTick = renderTick;
        this.partialTick = partialTick;
        this.camera = camera;
        this.frustum = frustum;
    }

    public Stage getStage() {
        return this.stage;
    }

    public LevelRenderer getLevelRenderer() {
        return this.levelRenderer;
    }

    public PoseStack getPoseStack() {
        return this.poseStack;
    }

    public Matrix4f getModelViewMatrix() {
        return this.modelViewMatrix;
    }

    public Matrix4f getProjectionMatrix() {
        return this.projectionMatrix;
    }

    public int getRenderTick() {
        return this.renderTick;
    }

    public DeltaTracker getPartialTick() {
        return this.partialTick;
    }

    public Camera getCamera() {
        return this.camera;
    }

    public Frustum getFrustum() {
        return this.frustum;
    }

    public static class Stage {
        private static final Map<ResourceLocation, Stage> STAGES = new ConcurrentHashMap<>();
        private static final Map<RenderType, Stage> RENDER_TYPE_STAGES = new ConcurrentHashMap<>();

        public static final Stage AFTER_SKY = register("after_sky", null);
        public static final Stage AFTER_SOLID_BLOCKS = register("after_solid_blocks", RenderType.solid());
        public static final Stage AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS = register("after_cutout_mipped_blocks", RenderType.cutoutMipped());
        public static final Stage AFTER_CUTOUT_BLOCKS = register("after_cutout_blocks", RenderType.cutout());
        public static final Stage AFTER_ENTITIES = register("after_entities", null);
        public static final Stage AFTER_BLOCK_ENTITIES = register("after_block_entities", null);
        public static final Stage AFTER_TRANSLUCENT_BLOCKS = register("after_translucent_blocks", RenderType.translucent());
        public static final Stage AFTER_TRIPWIRE_BLOCKS = register("after_tripwire_blocks", RenderType.tripwire());
        public static final Stage AFTER_PARTICLES = register("after_particles", null);
        public static final Stage AFTER_WEATHER = register("after_weather", null);
        public static final Stage AFTER_LEVEL = register("after_level", null);

        private final String name;

        private Stage(String name) {
            this.name = name;
        }

        private static Stage register(String name, RenderType renderType) {
            ResourceLocation id = ResourceLocation.parse(name);
            Stage stage = new Stage(id.toString());
            if (STAGES.putIfAbsent(id, stage) != null) throw new IllegalArgumentException("Attempted to replace an existing RenderLevelStageEvent.Stage: " + id);
            if (renderType != null) RENDER_TYPE_STAGES.put(renderType, stage);
            return stage;
        }

        @Override
        public String toString() {
            return this.name;
        }

        public static Stage fromRenderType(RenderType renderType) {
            return RENDER_TYPE_STAGES.get(renderType);
        }
    }
}
