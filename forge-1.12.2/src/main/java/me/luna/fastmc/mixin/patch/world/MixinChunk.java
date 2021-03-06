package me.luna.fastmc.mixin.patch.world;

import me.luna.fastmc.mixin.IPatchedChunk;
import me.luna.fastmc.mixin.IPatchedWorld;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import static net.minecraft.world.chunk.Chunk.NULL_BLOCK_STORAGE;

@Mixin(Chunk.class)
public abstract class MixinChunk implements IPatchedChunk {
    @Shadow
    @Final
    private ExtendedBlockStorage[] storageArrays;

    @Shadow
    @Final
    private int[] heightMap;

    @Shadow
    @Final
    private World world;

    @Shadow
    private boolean dirty;

    /**
     * @author Luna
     * @reason Memory allocation optimization
     */
    @Overwrite
    private void updateSkylightNeighborHeight(int x, int z, int startY, int endY) {
        IPatchedWorld patchedWorld = (IPatchedWorld) this.world;
        if (endY > startY && patchedWorld.isAreaLoaded(x, 0, z, 16, true)) {
            for (int y = startY; y < endY; ++y) {
                patchedWorld.checkLightFor(EnumSkyBlock.SKY, x, y, z);
            }
            this.dirty = true;
        }
    }

    @Override
    public int getLightFor(@NotNull EnumSkyBlock type, int x, int y, int z) {
        x = x & 15;
        z = z & 15;
        ExtendedBlockStorage extendedblockstorage = this.storageArrays[y >> 4];

        if (extendedblockstorage == NULL_BLOCK_STORAGE) {
            return this.canSeeSky(x, y, z) ? type.defaultLightValue : 0;
        } else if (type == EnumSkyBlock.SKY) {
            return !this.world.provider.hasSkyLight() ? 0 : extendedblockstorage.getSkyLight(x, y & 15, z);
        } else {
            return type == EnumSkyBlock.BLOCK ? extendedblockstorage.getBlockLight(x, y & 15, z) : type.defaultLightValue;
        }
    }

    @Override
    public boolean canSeeSky(int x, int y, int z) {
        return y >= this.heightMap[(z & 15) << 4 | x & 15];
    }
}
