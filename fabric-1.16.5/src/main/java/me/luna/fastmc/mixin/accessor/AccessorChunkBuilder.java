package me.luna.fastmc.mixin.accessor;

import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkBuilder.class)
public interface AccessorChunkBuilder {
    @Accessor
    int getQueuedTaskCount();
}