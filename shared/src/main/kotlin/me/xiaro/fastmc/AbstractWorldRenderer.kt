package me.xiaro.fastmc

import org.joml.Matrix4f

abstract class AbstractWorldRenderer: IRenderer {
    lateinit var tileEntityRenderer: AbstractTileEntityRenderer<*>; private set

    override var renderPosX = 0.0
    override var renderPosY = 0.0
    override var renderPosZ = 0.0

    override var projectionMatrix = Matrix4f()
    override var modelViewMatrix = Matrix4f()

    fun init(tileEntityRenderer: AbstractTileEntityRenderer<*>) {
        assert(!this::tileEntityRenderer.isInitialized)
        this.tileEntityRenderer = tileEntityRenderer
    }

    fun setupCamera(projection: Matrix4f, modelView: Matrix4f) {
        projectionMatrix = projection
        modelViewMatrix = modelView
    }

    abstract fun onPostTick()

    abstract fun preRender(partialTicks: Float)

    abstract fun postRender()
}