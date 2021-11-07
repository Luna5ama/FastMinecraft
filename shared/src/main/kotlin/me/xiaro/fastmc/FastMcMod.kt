package me.xiaro.fastmc

import me.xiaro.fastmc.font.IFontRendererWrapper
import me.xiaro.fastmc.opengl.IGLWrapper
import me.xiaro.fastmc.resource.IResourceManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object FastMcMod {
    val logger: Logger = LogManager.getLogger("Fast Minecraft")
    var isInitialized = false; private set

    lateinit var glWrapper: IGLWrapper; private set

    lateinit var resourceManager: IResourceManager; private set
    lateinit var worldRenderer: AbstractWorldRenderer; private set
    lateinit var fontRenderer: IFontRendererWrapper; private set

    fun initGLWrapper(glWrapper: IGLWrapper) {
        this.glWrapper = glWrapper
    }

    fun init(resourceManager: IResourceManager, worldRenderer: AbstractWorldRenderer, fontRenderer: IFontRendererWrapper) {
        if (isInitialized) error("Already initialized!")

        this.resourceManager = resourceManager
        this.worldRenderer = worldRenderer
        this.fontRenderer = fontRenderer

        isInitialized = true
    }

    fun reloadEntityRenderer(resourceManager: IResourceManager, entityRenderer: AbstractWorldRenderer) {
        if (isInitialized) {
            this.resourceManager.destroy()
        }

        this.resourceManager = resourceManager
        this.worldRenderer = entityRenderer
    }

    fun reloadResource(resourceManager: IResourceManager, entityRenderer: AbstractWorldRenderer, fontRenderer: IFontRendererWrapper) {
//        isInitialized = true

        if (isInitialized) {
            this.resourceManager.destroy()
            this.fontRenderer.destroy()
        }

        this.resourceManager = resourceManager
        this.worldRenderer = entityRenderer
        this.fontRenderer = fontRenderer

        isInitialized = false
    }

    fun onPostTick() {
        worldRenderer.onPostTick()
    }
}