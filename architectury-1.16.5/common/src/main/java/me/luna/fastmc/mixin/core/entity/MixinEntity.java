package me.luna.fastmc.mixin.core.entity;

import me.luna.fastmc.entity.EntityInfo;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class MixinEntity implements EntityInfo<Entity> {
    private final int typeID = EntityInfo.super.getTypeID();

    @Override
    @NotNull
    public Entity getEntity() {
        return (Entity) ((Object) this);
    }

    @Override
    public int getTypeID() {
        return typeID;
    }
}
