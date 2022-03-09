package me.luna.fastmc.renderer

import me.luna.fastmc.shared.opengl.glBindTexture
import me.luna.fastmc.shared.opengl.glBindVertexArray
import me.luna.fastmc.shared.opengl.glUniform1f
import me.luna.fastmc.shared.opengl.glUseProgramForce
import me.luna.fastmc.shared.renderer.AbstractWorldRenderer
import me.luna.fastmc.shared.resource.IResourceManager
import me.luna.fastmc.shared.util.MathUtils
import me.luna.fastmc.shared.util.MatrixUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11.*

class WorldRenderer(private val mc: Minecraft, override val resourceManager: IResourceManager) :
    AbstractWorldRenderer() {
    override fun onPostTick() {
        mc.profiler.startSection("worldRenderer")

        mc.profiler.startSection("entity")
        entityRenderer.onPostTick()
        mc.profiler.endStartSection("tileEntity")
        tileEntityRenderer.onPostTick()
        mc.profiler.endSection()

        mc.profiler.endSection()
    }

    override fun preRender(partialTicks: Float) {
        glUseProgramForce(0)

        MatrixUtils.putMatrix(projectionMatrix)

        resourceManager.entityShader.resources.forEach {
            it.bind()
            glUniform1f(it.partialTicksUniform, partialTicks)
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