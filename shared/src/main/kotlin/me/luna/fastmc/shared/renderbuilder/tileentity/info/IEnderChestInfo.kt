package me.luna.fastmc.shared.renderbuilder.tileentity.info

interface IEnderChestInfo<E : Any> : IHDirectionalTileEntityInfo<E> {
    val prevLidAngle: Float
    val lidAngle: Float
}