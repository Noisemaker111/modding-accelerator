package ${package};

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * NeoForge's net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper: a VertexConsumer that hands every vertex
 * element to the parent consumer, for a mod to subclass and change some of them. NeoForge's misc(element, values) is a
 * method NeoForge adds to the game's VertexConsumer; Fabric's has none, so it is not passed on.
 */
public abstract class VertexConsumerWrapper implements VertexConsumer {

    protected final VertexConsumer parent;

    public VertexConsumerWrapper(VertexConsumer parent) {
        this.parent = parent;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        parent.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int packedColor) {
        parent.setColor(packedColor);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        parent.setColor(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        parent.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        parent.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        parent.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        parent.setUv3(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        parent.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float lineWidth) {
        parent.setLineWidth(lineWidth);
        return this;
    }
}
