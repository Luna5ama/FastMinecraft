package me.luna.fastmc.tileentity

import me.luna.fastmc.shared.renderbuilder.tileentity.info.IEnderChestInfo
import me.luna.fastmc.util.TileEntityEnderChest
import net.minecraft.block.EnderChestBlock
import net.minecraft.state.property.EnumProperty
import net.minecraft.util.math.Direction

interface EnderChestInfo : HDirectionalTileEntityInfo<TileEntityEnderChest>, IEnderChestInfo<TileEntityEnderChest> {
    override val property: EnumProperty<Direction>
        get() = EnderChestBlock.FACING

    override val prevLidAngle: Float
        get() = entity.getAnimationProgress(0.0f)

    override val lidAngle: Float
        get() = entity.getAnimationProgress(1.0f)
}