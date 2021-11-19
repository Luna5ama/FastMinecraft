package me.xiaro.fastmc.shared.renderbuilder.entity.info

interface ILivingBaseInfo<E> : IEntityInfo<E> {
    val deathTime: Int

    val limbSwing: Float
    val limbSwingAmount: Float
    val prevLimbSwingAmount: Float

    val rotationYawHead: Float
    val prevRotationYawHead: Float

    val renderYawOffset: Float
    val prevRenderYawOffset: Float
}