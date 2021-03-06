package me.luna.fastmc.mixin

import me.luna.fastmc.shared.util.collection.FastObjectArrayList
import net.minecraft.tileentity.TileEntity

interface IPatchedCompiledChunk {
    val instancingRenderTileEntities: FastObjectArrayList<TileEntity>
}