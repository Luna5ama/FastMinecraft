package me.luna.fastmc.mixin.patch.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import me.luna.fastmc.mixin.IPatchedBuiltChunk;
import me.luna.fastmc.mixin.IPatchedBuiltChunkStorage;
import me.luna.fastmc.mixin.IPatchedRenderLayer;
import me.luna.fastmc.mixin.IPatchedWorldRenderer;
import me.luna.fastmc.mixin.accessor.AccessorVertexBuffer;
import me.luna.fastmc.shared.util.*;
import me.luna.fastmc.shared.util.collection.ExtendedBitSet;
import me.luna.fastmc.shared.util.collection.FastObjectArrayList;
import me.luna.fastmc.util.AdaptersKt;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;

@Mixin(WorldRenderer.class)
public abstract class MixinPatchWorldRenderer implements IPatchedWorldRenderer {
    private final DoubleBufferedCollection<FastObjectArrayList<BlockEntity>> renderTileEntityList = new DoubleBufferedCollection<>(new FastObjectArrayList<>(), FastObjectArrayList::clearFast);
    private final DoubleBufferedCollection<ExtendedBitSet> chunksToUpdateBitSet = new DoubleBufferedCollection<>(new ExtendedBitSet(), it -> {});
    private final DoubleBuffered<FastObjectArrayList<ChunkBuilder.BuiltChunk>[]> filteredRenderInfos = new DoubleBuffered<>(getArray(), getArray(), MixinPatchWorldRenderer::clearArray);
    private final Matrix4f original = new Matrix4f();
    private final Matrix4f translated = new Matrix4f();
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
    private BuiltChunkStorage chunks;
    private int lastCameraX0 = Integer.MAX_VALUE;
    private int lastCameraY0 = Integer.MAX_VALUE;
    private int lastCameraZ0 = Integer.MAX_VALUE;
    private int lastCameraYaw0 = Integer.MAX_VALUE;
    private int lastCameraPitch0 = Integer.MAX_VALUE;

    private final ThreadPoolExecutor updateChunkThreadPool = new ThreadPoolExecutor(
        0,
        1,
        15L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),
        it -> new Thread(it, "Chunk Update")
    );

    private static FastObjectArrayList<ChunkBuilder.BuiltChunk>[] getArray() {
        int size = RenderLayer.getBlockLayers().size();
        ArrayList<FastObjectArrayList<ChunkBuilder.BuiltChunk>> list = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            list.add(new FastObjectArrayList<>());
        }

        //noinspection unchecked
        return (FastObjectArrayList<ChunkBuilder.BuiltChunk>[]) list.toArray(new FastObjectArrayList[size]);
    }

    private static void clearArray(FastObjectArrayList<ChunkBuilder.BuiltChunk>[] array) {
        for (int i = 0; i < RenderLayer.getBlockLayers().size(); i++) {
            array[i].clearFast();
        }
    }

    private static void clearAndTrimArray(FastObjectArrayList<ChunkBuilder.BuiltChunk>[] array) {
        for (int i = 0; i < RenderLayer.getBlockLayers().size(); i++) {
            array[i].clearAndTrim();
        }
    }

    @Shadow
    public abstract void reload();

    @Shadow
    public abstract void render(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, net.minecraft.util.math.Matrix4f matrix4f);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init$Inject$RETURN(MinecraftClient client, BufferBuilderStorage bufferBuilders, CallbackInfo ci) {
        this.visibleChunks = new FastObjectArrayList<>(69696);
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    public void setWorld$Inject$HEAD(@Nullable ClientWorld world, CallbackInfo ci) {
        if (world == null) {
            renderTileEntityList.get().clearAndTrim();
            renderTileEntityList.swapAndGet().clearAndTrim();

            clearAndTrimArray(filteredRenderInfos.get());
            clearAndTrimArray(filteredRenderInfos.swapAndGet());

            chunksToUpdateBitSet.get().clearFast();
            chunksToUpdateBitSet.swapAndGet().clearFast();
        }

        lastCameraX0 = Integer.MAX_VALUE;
        lastCameraY0 = Integer.MAX_VALUE;
        lastCameraZ0 = Integer.MAX_VALUE;
        lastCameraYaw0 = Integer.MAX_VALUE;
        lastCameraPitch0 = Integer.MAX_VALUE;

        updateChunkThreadPool.getQueue().clear();
    }

    /**
     * @author Luna
     * @reason Setup terrain optimization
     */
    @Overwrite
    private void setupTerrain(Camera camera, Frustum frustum, boolean hasForcedFrustum, int frame, boolean spectator) {
        WorldRenderer thisRef = (WorldRenderer) (Object) this;
        IPatchedBuiltChunkStorage patchedChunks = (IPatchedBuiltChunkStorage) this.chunks;

        if (this.client.options.viewDistance != this.viewDistance) {
            this.reload();
        }

        this.world.getProfiler().push("camera");
        assert this.client.player != null;
        double cameraUpdateDeltaX = this.client.player.getX() - this.lastCameraChunkUpdateX;
        double cameraUpdateDeltaY = this.client.player.getY() - this.lastCameraChunkUpdateY;
        double cameraUpdateDeltaZ = this.client.player.getZ() - this.lastCameraChunkUpdateZ;

        if (this.cameraChunkX != this.client.player.chunkX
            || this.cameraChunkY != this.client.player.chunkY
            || this.cameraChunkZ != this.client.player.chunkZ
            || cameraUpdateDeltaX * cameraUpdateDeltaX + cameraUpdateDeltaY * cameraUpdateDeltaY + cameraUpdateDeltaZ * cameraUpdateDeltaZ > 16.0D) {
            this.lastCameraChunkUpdateX = this.client.player.getX();
            this.lastCameraChunkUpdateY = this.client.player.getY();
            this.lastCameraChunkUpdateZ = this.client.player.getZ();
            this.cameraChunkX = this.client.player.chunkX;
            this.cameraChunkY = this.client.player.chunkY;
            this.cameraChunkZ = this.client.player.chunkZ;
            this.chunks.updateCameraPosition(this.client.player.getX(), this.client.player.getZ());
        }

        Vec3d cameraPos = camera.getPos();
        this.chunkBuilder.setCameraPosition(cameraPos);

        this.client.getProfiler().swap("culling");
        BlockPos cameraBlockPos = camera.getBlockPos();

        int cameraPosX0 = cameraBlockPos.getX() >> 1;
        int cameraPosY0 = cameraBlockPos.getY() >> 1;
        int cameraPosZ0 = cameraBlockPos.getZ() >> 1;
        int cameraYaw0 = MathHelper.floor(camera.getYaw());
        int cameraPitch0 = MathUtilsKt.fastFloor(camera.getPitch());

        if (!hasForcedFrustum
            && (this.needsTerrainUpdate
            || cameraPosX0 != this.lastCameraX0
            || cameraPosY0 != this.lastCameraY0
            || cameraPosZ0 != this.lastCameraZ0
            || cameraYaw0 != this.lastCameraYaw0
            || cameraPitch0 != this.lastCameraPitch0)) {
            this.lastCameraX0 = cameraPosX0;
            this.lastCameraY0 = cameraPosY0;
            this.lastCameraZ0 = cameraPosZ0;
            this.lastCameraYaw0 = cameraYaw0;
            this.lastCameraPitch0 = cameraPitch0;

            this.needsTerrainUpdate = false;

            this.visibleChunks.clear();
            clearRenderLists();

            Entity.setRenderDistanceMultiplier(MathHelper.clamp(this.client.options.viewDistance / 8.0D, 1.0D, 2.5D) * this.client.options.entityDistanceScaling);
            boolean chunkCulling = this.client.chunkCullingEnabled;

            ChunkBuilder.BuiltChunk builtChunk = patchedChunks.getRenderedChunk(cameraBlockPos);
            ObjectArrayList<WorldRenderer.ChunkInfo> list = new ObjectArrayList<>();

            if (builtChunk != null) {
                if (spectator && this.world.getBlockState(cameraBlockPos).isOpaqueFullCube(this.world, cameraBlockPos)) {
                    chunkCulling = false;
                }

                builtChunk.setRebuildFrame(frame);
                list.add(thisRef.new ChunkInfo(builtChunk, null, 0));
            } else {
                int chunkY = cameraBlockPos.getY() > 0 ? 248 : 8;
                int cameraChunkX = cameraBlockPos.getX() >> 4;
                int cameraChunkZ = cameraBlockPos.getZ() >> 4;
                Comparator<WorldRenderer.ChunkInfo> comparator = Comparator.comparingInt(it -> {
                    BlockPos chunkPos = it.chunk.getOrigin();
                    int diffX = cameraBlockPos.getX() - (chunkPos.getX() + 8);
                    int diffY = cameraBlockPos.getY() - (chunkPos.getY() + 8);
                    int diffZ = cameraBlockPos.getZ() - (chunkPos.getZ() + 8);
                    return diffX * diffX + diffY * diffY + diffZ * diffZ;
                });

                for (int iChunkX = -this.viewDistance; iChunkX <= this.viewDistance; iChunkX++) {
                    for (int iChunkZ = -this.viewDistance; iChunkZ <= this.viewDistance; iChunkZ++) {
                        ChunkBuilder.BuiltChunk builtChunk2 = patchedChunks.getRenderedChunk(cameraChunkX + iChunkX, chunkY, cameraChunkZ + iChunkZ);
                        if (builtChunk2 != null && frustum.isVisible(builtChunk2.boundingBox)) {
                            builtChunk2.setRebuildFrame(frame);
                            list.add(thisRef.new ChunkInfo(builtChunk2, null, 0));
                        }
                    }
                }

                Arrays.sort(list.elements(), 0, list.size(), comparator);
            }

            this.client.getProfiler().push("iteration");
            BlockPos cameraChunkBlockPos = new BlockPos(
                cameraBlockPos.getX() >> 4 << 4,
                cameraBlockPos.getY() >> 4 << 4,
                cameraBlockPos.getZ() >> 4 << 4
            );
            this.setupTerrainIteration((FastObjectArrayList<WorldRenderer.ChunkInfo>) visibleChunks, renderTileEntityList.get(), filteredRenderInfos.get(), frustum, frame, cameraChunkBlockPos, chunkCulling, list);
            list.clear();
            this.client.getProfiler().pop();
        }

        this.client.getProfiler().swap("rebuildNear");
        this.rebuildNear(cameraBlockPos);
        this.client.getProfiler().pop();
    }

    private void rebuildNear(BlockPos cameraBlockPos) {
        ExtendedBitSet oldSet = chunksToUpdateBitSet.get();
        ExtendedBitSet newSet = chunksToUpdateBitSet.swapAndGet();
        FastObjectArrayList<ChunkBuilder.BuiltChunk> list = new FastObjectArrayList<>();

        for (ChunkBuilder.BuiltChunk builtChunk : this.chunksToRebuild) {
            int index = ((IPatchedBuiltChunk) builtChunk).getIndex();
            if (newSet.add(index)) {
                list.add(builtChunk);
            }
        }

        for (WorldRenderer.ChunkInfo renderInfo : this.visibleChunks) {
            ChunkBuilder.BuiltChunk builtChunk = renderInfo.chunk;
            int index = ((IPatchedBuiltChunk) builtChunk).getIndex();

            if (builtChunk.needsRebuild() || oldSet.contains(index)) {
                if (newSet.add(index)) {
                    list.add(builtChunk);
                }
            }
        }

        oldSet.clear();
        list.trim();

        Arrays.sort(list.elements(), Comparator.comparingInt(it -> {
            BlockPos chunkPos = it.getOrigin();
            int diffX = cameraBlockPos.getX() - (chunkPos.getX() + 8);
            int diffY = cameraBlockPos.getY() - (chunkPos.getY() + 8);
            int diffZ = cameraBlockPos.getZ() - (chunkPos.getZ() + 8);
            return diffX * diffX + diffY * diffY + diffZ * diffZ;
        }));

        this.chunksToRebuild = new ObjectArraySet<>(list.elements());
    }

    /**
     * @author Luna
     * @reason Chunk update optimization
     */
    @Overwrite
    private void updateChunks(long limitTime) {
        this.needsTerrainUpdate = this.chunkBuilder.upload() || this.needsTerrainUpdate;
        if (this.chunksToRebuild.isEmpty() || updateChunkThreadPool.getTaskCount() - updateChunkThreadPool.getCompletedTaskCount() >= 4) return;

        FastObjectArrayList<ChunkBuilder.BuiltChunk> list = new FastObjectArrayList<>();
        Iterator<ChunkBuilder.BuiltChunk> iterator = this.chunksToRebuild.iterator();
        ExtendedBitSet bitSet = chunksToUpdateBitSet.get();

        while (list.size() < ParallelUtils.CPU_THREADS * 2 && iterator.hasNext()) {
            ChunkBuilder.BuiltChunk builtChunk = iterator.next();
            list.add(builtChunk);
            builtChunk.cancelRebuild();
            iterator.remove();
            bitSet.remove(((IPatchedBuiltChunk) builtChunk).getIndex());
        }

        updateChunkThreadPool.submit(() -> {
            for (int i = 0; i < list.size(); i++) {
                ChunkBuilder.BuiltChunk builtChunk = list.get(i);
                builtChunk.scheduleRebuild(this.chunkBuilder);
                builtChunk.cancelRebuild();
            }
        });
    }

    private void clearRenderLists() {
        renderTileEntityList.getAndSwap();
        int entityCapacity = world.blockEntities.size();
        entityCapacity = MathUtils.ceilToPOT(entityCapacity + entityCapacity / 2);

        FastObjectArrayList<BlockEntity> renderTileEntityList = this.renderTileEntityList.get();

        if (renderTileEntityList.getCapacity() > entityCapacity << 1) {
            renderTileEntityList.trim(entityCapacity);
        } else {
            renderTileEntityList.ensureCapacity(entityCapacity);
        }

        filteredRenderInfos.getAndSwap();
        FastObjectArrayList<ChunkBuilder.BuiltChunk>[] array = filteredRenderInfos.get();
        int renderInfoSize = this.visibleChunks.size();

        for (int i = 0; i < RenderLayer.getBlockLayers().size(); i++) {
            FastObjectArrayList<ChunkBuilder.BuiltChunk> list = array[i];

            if (list.getCapacity() > renderInfoSize + renderInfoSize / 2) {
                renderTileEntityList.trim(renderInfoSize);
            } else {
                list.ensureCapacity(renderInfoSize);
            }
        }
    }

    @NotNull
    @Override
    public List<BlockEntity> getRenderTileEntityList() {
        return renderTileEntityList.get();
    }

    /**
     * @author Luna
     * @reason Batch optimization
     */
    @SuppressWarnings("deprecation")
    @Overwrite
    private void renderLayer(RenderLayer layer, MatrixStack matrixStack, double renderPosX, double renderPosY, double renderPosZ) {
        layer.startDrawing();
        if (layer == RenderLayer.getTranslucent()) {
            this.client.getProfiler().push("translucentSort");
            double xDiff = renderPosX - this.lastTranslucentSortX;
            double yDiff = renderPosY - this.lastTranslucentSortY;
            double zDiff = renderPosZ - this.lastTranslucentSortZ;
            if (xDiff * xDiff + yDiff * yDiff + zDiff * zDiff > 1.0D) {
                this.lastTranslucentSortX = renderPosX;
                this.lastTranslucentSortY = renderPosY;
                this.lastTranslucentSortZ = renderPosZ;
                int count = 0;

                for (WorldRenderer.ChunkInfo chunkInfo : this.visibleChunks) {
                    if (count >= 16) break;
                    if (chunkInfo.chunk.scheduleSort(layer, this.chunkBuilder)) {
                        ++count;
                    }
                }
            }

            this.client.getProfiler().pop();
        }

        this.client.getProfiler().push(layer::toString);
        FastObjectArrayList<ChunkBuilder.BuiltChunk> list = filteredRenderInfos.get()[((IPatchedRenderLayer) layer).getIndex()];

        AdaptersKt.toJoml(matrixStack.peek().getModel(), original);
        RenderSystem.pushMatrix();

        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_COLOR_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glClientActiveTexture(GL_TEXTURE2);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glClientActiveTexture(GL_TEXTURE0);

        if (layer != RenderLayer.getTranslucent()) {
            for (int i = 0; i < list.size(); i++) {
                renderLayer(layer, renderPosX, renderPosY, renderPosZ, list.get(i));
            }
        } else {
            for (int i = list.size() - 1; i >= 0; i--) {
                renderLayer(layer, renderPosX, renderPosY, renderPosZ, list.get(i));
            }
        }

        glDisableClientState(GL_VERTEX_ARRAY);
        glDisableClientState(GL_COLOR_ARRAY);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glClientActiveTexture(GL_TEXTURE2);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glClientActiveTexture(GL_TEXTURE0);

        RenderSystem.popMatrix();
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        RenderSystem.clearCurrentColor();
        this.client.getProfiler().pop();
        layer.endDrawing();
    }

    @SuppressWarnings("deprecation")
    private void renderLayer(RenderLayer layer, double renderPosX, double renderPosY, double renderPosZ, ChunkBuilder.BuiltChunk builtChunk) {
        VertexBuffer vertexBuffer = builtChunk.getBuffer(layer);
        AccessorVertexBuffer accessor = ((AccessorVertexBuffer) vertexBuffer);

        RenderSystem.loadIdentity();
        BlockPos blockPos = builtChunk.getOrigin();
        original.translate((float) (blockPos.getX() - renderPosX), (float) (blockPos.getY() - renderPosY), (float) (blockPos.getZ() - renderPosZ), translated);
        MatrixUtils.INSTANCE.putMatrix(translated);
        GlStateManager.multMatrix(MatrixUtils.INSTANCE.getMatrixBuffer());

        glBindBuffer(GL_ARRAY_BUFFER, accessor.getVertexBufferId());
        glVertexPointer(3, GL_FLOAT, 32, 0);
        glColorPointer(4, GL_UNSIGNED_BYTE, 32, 12);
        glTexCoordPointer(2, GL_FLOAT, 32, 16);
        glClientActiveTexture(GL_TEXTURE2);
        glTexCoordPointer(2, GL_SHORT, 32, 24);
        glClientActiveTexture(GL_TEXTURE0);

        RenderSystem.drawArrays(GL_QUADS, 0, accessor.getVertexCount());
    }
}