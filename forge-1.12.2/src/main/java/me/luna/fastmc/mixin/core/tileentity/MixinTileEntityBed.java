package me.luna.fastmc.mixin.core.tileentity;

import me.luna.fastmc.tileentity.BedInfo;
import me.luna.fastmc.tileentity.ChestInfo;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntityBed;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TileEntityBed.class)
public abstract class MixinTileEntityBed implements BedInfo {

}