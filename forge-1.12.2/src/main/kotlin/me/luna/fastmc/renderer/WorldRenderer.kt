package me.luna.fastmc.renderer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.luna.fastmc.shared.opengl.glBindTexture
import me.luna.fastmc.shared.opengl.glBindVertexArray
import me.luna.fastmc.shared.opengl.glProgramUniform1f
import me.luna.fastmc.shared.opengl.glUseProgramForce
import me.luna.fastmc.shared.renderer.AbstractWorldRenderer
import me.luna.fastmc.shared.resource.IResourceManager
import me.luna.fastmc.shared.util.FastMcCoreScope
import me.luna.fastmc.shared.util.MathUtils
import me.luna.fastmc.shared.util.MatrixUtils
import net.minecraft.client.Minecraft
import kotlin.coroutines.CoroutineContext

class WorldRenderer(private val mc: Minecraft, override val resourceManager: IResourceManager) :
    AbstractWorldRenderer() {
    override fun onPostTick(mainThreadContext: CoroutineContext, parentScope: CoroutineScope) {
        parentScope.launch(FastMcCoreScope.context) {
            entityRenderer.onPostTick(mainThreadContext, this)
        }
        parentScope.launch(FastMcCoreScope.context) {
            tileEntityRenderer.onPostTick(mainThreadContext, this)
        }
    }

    override fun preRender(partialTicks: Float) {
        glUseProgramForce(0)

        MatrixUtils.putMatrix(projectionMatrix)

        resourceManager.entityShader.resources.forEach {
            glProgramUniform1f(it.id, it.partialTicksUniform, partialTicks)
            it.updateProjectionMatrix()
        }

        val entity = mc.renderViewEntity ?: mc.player
        if (entity != null) {
            renderPosX = MathUtils.lerp(entity.lastTickPosX, entity.posX, partialTicks)
            renderPosY = MathUtils.lerp(entity.lastTickPosY, entity.posY, partialTicks)
            renderPosZ = MathUtils.lerp(entity.lastTickPosZ, entity.posZ, partialTicks)
        }
    }

    override fun postRender() {
        glBindVertexArray(0)
        glBindTexture(0)
        glUseProgramForce(0)
    }
}