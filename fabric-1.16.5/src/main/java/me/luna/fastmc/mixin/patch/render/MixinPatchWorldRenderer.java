package me.luna.fastmc.mixin.patch.render;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import me.luna.fastmc.mixin.IPatchedBuiltChunk;
import me.luna.fastmc.mixin.IPatchedRenderLayer;
import me.luna.fastmc.mixin.IPatchedWorldRenderer;
import me.luna.fastmc.mixin.PatchedWorldRenderer;
import me.luna.fastmc.shared.terrain.TerrainRenderer;
import me.luna.fastmc.shared.util.FastMcExtendScope;
import me.luna.fastmc.shared.util.collection.ExtendedBitSet;
import me.luna.fastmc.terrain.ChunkVertexData;
import me.luna.fastmc.terrain.RegionBuiltChunkStorage;
import me.luna.fastmc.terrain.RenderRegion;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL14.glMultiDrawArrays;

@Mixin(WorldRenderer.class)
public abstract class MixinPatchWorldRenderer implements IPatchedWorldRenderer {
    @Shadow
    private ChunkBuilder chunkBuilder;
    @Shadow
    private boolean needsTerrainUpdate;
    @Shadow
    private Set<ChunkBuilder.BuiltChunk> chunksToRebuild;
    @Mutable
    @Shadow
    @Final
    private ObjectList<WorldRenderer.ChunkInfo> visibleChunks;
    @Shadow
    @Final
    private MinecraftClient client;
    @Shadow
    private ClientWorld world;
    @Shadow
    private double lastTranslucentSortX;
    @Shadow
    private double lastTranslucentSortY;
    @Shadow
    private double lastTranslucentSortZ;
    @Shadow
    private int viewDistance;
    @Shadow
    private BuiltChunkStorage chunks;
    @Shadow
    @Final
    private Set<BlockEntity> noCullingBlockEntities;
    @Shadow
    private boolean cloudsDirty;
    @Shadow
    @Final
    private BufferBuilderStorage bufferBuilders;
    @Shadow
    private double lastCameraChunkUpdateX;
    @Shadow
    private double lastCameraChunkUpdateY;
    @Shadow
    private double lastCameraChunkUpdateZ;
    @Shadow
    private int cameraChunkX;
    @Shadow
    private int cameraChunkY;
    @Shadow
    private int cameraChunkZ;

    @Shadow
    public abstract void render(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, net.minecraft.util.math.Matrix4f matrix4f);

    @Shadow
    public abstract void setWorld(@Nullable ClientWorld world);

    @Shadow
    protected abstract void loadTransparencyShader();

    @Shadow
    protected abstract void resetTransparencyShader();

    @Shadow protected abstract void checkEmpty(MatrixStack matrices);

    private final PatchedWorldRenderer patch = new PatchedWorldRenderer((WorldRenderer) (Object) this);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init$Inject$RETURN(MinecraftClient client, BufferBuilderStorage bufferBuilders, CallbackInfo ci) {
        this.visibleChunks = ObjectLists.emptyList();
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    public void setWorld$Inject$HEAD(@Nullable ClientWorld world, CallbackInfo ci) {
        if (world == null) {
            patch.clear();
        }
    }

    /**
     * @author Luna
     * @reason Render override
     */
    @SuppressWarnings("SynchronizeOnNonFinalField")
    @Overwrite
    public void reload() {
        if (this.world != null) {
            WorldRenderer thisRef = (WorldRenderer) (Object) this;

            if (MinecraftClient.isFabulousGraphicsOrBetter()) {
                this.loadTransparencyShader();
            } else {
                this.resetTransparencyShader();
            }

            this.world.reloadColor();
            if (this.chunkBuilder == null) {
                this.chunkBuilder = new ChunkBuilder(this.world, thisRef, FastMcExtendScope.INSTANCE.getPool(), this.client.is64Bit(), this.bufferBuilders.getBlockBufferBuilders());
            } else {
                this.chunkBuilder.setWorld(this.world);
            }

            this.needsTerrainUpdate = true;
            this.cloudsDirty = true;
            RenderLayers.setFancyGraphicsOrBetter(MinecraftClient.isFancyGraphicsOrBetter());
            this.viewDistance = this.client.options.viewDistance;
            if (this.chunks != null) {
                this.chunks.clear();
            }

            this.clearChunkRenderers();
            synchronized (this.noCullingBlockEntities) {
                this.noCullingBlockEntities.clear();
            }

            this.chunks = new RegionBuiltChunkStorage(this.chunkBuilder, this.world, this.client.options.viewDistance, thisRef);

            this.lastCameraChunkUpdateX = Double.MAX_VALUE;
            this.lastCameraChunkUpdateY = Double.MAX_VALUE;
            this.lastCameraChunkUpdateZ = Double.MAX_VALUE;
            this.cameraChunkX = Integer.MAX_VALUE;
            this.cameraChunkY = Integer.MAX_VALUE;
            this.cameraChunkZ = Integer.MAX_VALUE;

            this.lastTranslucentSortX = Double.MAX_VALUE;
            this.lastTranslucentSortY = Double.MAX_VALUE;
            this.lastTranslucentSortZ = Double.MAX_VALUE;

            patch.clear();
            patch.resize(this.chunks.chunks.length);
        }
    }

    /**
     * @author Luna
     * @reason Setup terrain optimization
     */
    @Overwrite
    private void setupTerrain(Camera camera, Frustum frustum, boolean hasForcedFrustum, int frame, boolean spectator) {
        patch.setupTerrain0(camera, frustum, hasForcedFrustum, spectator);
    }

    /**
     * @author Luna
     * @reason Chunk update optimization
     */
    @Overwrite
    public void clearChunkRenderers() {
        chunksToRebuild.clear();
        chunkBuilder.reset();
    }

    /**
     * @author Luna
     * @reason Chunk update optimization
     */
    @Overwrite
    public boolean isTerrainRenderComplete() {
        return this.chunkBuilder.isEmpty() && patch.getUpdatingChunkBitSet().get().isEmpty();
    }

    /**
     * @author Luna
     * @reason Batch optimization
     */
    @Overwrite
    private void renderLayer(RenderLayer layer, MatrixStack matrixStack, double renderPosX, double renderPosY, double renderPosZ) {
        this.client.getProfiler().push(layer.name);

        int layerIndex = ((IPatchedRenderLayer) layer).getIndex();
        RenderRegion[] regionArray = ((RegionBuiltChunkStorage) this.chunks).getRegionArray();

        TerrainRenderer.TerrainShader terrainShader = TerrainRenderer.INSTANCE.getShader();

        for (int i = 0; i < regionArray.length; i++) {
            RenderRegion region = regionArray[i];
            if (!region.isVisible()) continue;
            RenderRegion.RegionLayer regionLayer = region.getRegionLayer(layerIndex);
            if (regionLayer == null) continue;

            BlockPos regionOrigin = region.getOrigin();
            terrainShader.setOffset(
                (float) (regionOrigin.getX() - renderPosX),
                (float) (regionOrigin.getY() - renderPosY),
                (float) (regionOrigin.getZ() - renderPosZ)
            );

            regionLayer.vao.bind();
            glMultiDrawArrays(GL_QUADS, regionLayer.firstBuffer.get(), regionLayer.countBuffer.get());
        }

        this.client.getProfiler().pop();
    }

    /**
     * @author Luna
     * @reason Debug
     */
    @Overwrite
    public String getChunksDebugString() {
        RenderRegion[] regionArray = ((RegionBuiltChunkStorage) this.chunks).getRegionArray();
        int visibleRegionCount = 0;
        int regionCount = 0;
        long visibleRegionVertexSize = 0;
        long regionVertexSize = 0;
        long regionVboSize = 0;
        for (int i = 0; i < regionArray.length; i++) {
            RenderRegion region = regionArray[i];
            RenderRegion.RegionLayer[] regionLayerArray = region.getRegionLayerArray();
            boolean isEmpty = true;

            for (int i2 = 0; i2 < regionLayerArray.length; i2++) {
                RenderRegion.RegionLayer regionLayer = regionLayerArray[i2];
                if (regionLayer != null) {
                    isEmpty = false;
                    if (region.isVisible()) visibleRegionVertexSize += regionLayer.vboInfo.vertexSize;
                    regionVertexSize += regionLayer.vboInfo.vertexSize;
                    regionVboSize += regionLayer.vboInfo.vbo.getSize();
                }
            }

            if (!isEmpty) {
                if (region.isVisible()) visibleRegionCount++;
                regionCount++;
            }
        }

        ChunkBuilder.BuiltChunk[] builtChunks = this.chunks.chunks;
        ExtendedBitSet visibleChunkBitSet = patch.getVisibleChunkBitSet().get();

        long chunkVertexSize = 0;
        long chunkVboSize = 0;
        int chunkCount = 0;

        long visibleChunkSize = 0;
        int visibleChunkCount = 0;

        for (int i = 0; i < builtChunks.length; i++) {
            IPatchedBuiltChunk patchedBuiltChunk = (IPatchedBuiltChunk) builtChunks[i];
            ChunkVertexData[] bufferArray = patchedBuiltChunk.getChunkVertexDataArray();
            boolean visible = visibleChunkBitSet.contains(patchedBuiltChunk.getIndex());
            boolean isEmpty = true;

            for (int i2 = 0; i2 < bufferArray.length; i2++) {
                ChunkVertexData data = bufferArray[i2];
                if (data != null) {
                    isEmpty = false;
                    chunkVertexSize += data.vboInfo.vertexSize;
                    chunkVboSize += data.vboInfo.vbo.getSize();
                    if (visible) visibleChunkSize += data.vboInfo.vertexSize;
                }
            }

            if (!isEmpty) {
                chunkCount++;
                if (visible) visibleChunkCount++;
            }
        }

        return String.format(
            "%sD: %d, R: %d/%d/%d(%.1f/%.1f/%.1f MB), C: %d/%d/%d(%.1f/%.1f/%.1f MB), %s",
            this.client.chunkCullingEnabled ? "(s) " : "",
            this.viewDistance,
            visibleRegionCount,
            regionCount,
            regionArray.length,
            (double) visibleRegionVertexSize / 1048576.0,
            (double) regionVertexSize / 1048576.0,
            (double) regionVboSize / 1048576.0,
            visibleChunkCount,
            chunkCount,
            builtChunks.length,
            (double) visibleChunkSize / 1048576.0,
            (double) chunkVertexSize / 1048576.0,
            (double) chunkVboSize / 1048576.0,
            this.chunkBuilder == null ? "null" : this.chunkBuilder.getDebugString()
        );
    }

    @NotNull
    @Override
    public PatchedWorldRenderer getPatch() {
        return patch;
    }
}
