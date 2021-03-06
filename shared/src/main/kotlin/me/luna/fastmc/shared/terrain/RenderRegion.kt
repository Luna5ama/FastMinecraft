package me.luna.fastmc.shared.terrain

import me.luna.fastmc.shared.opengl.*
import me.luna.fastmc.shared.opengl.impl.RenderBufferPool
import me.luna.fastmc.shared.util.SoftReferenceObjectPool
import me.luna.fastmc.shared.util.allocateByte
import me.luna.fastmc.shared.util.collection.FastObjectArrayList
import org.joml.FrustumIntersection
import sun.misc.Unsafe
import java.nio.Buffer
import java.nio.ByteBuffer

@Suppress("NOTHING_TO_INLINE")
class RenderRegion(
    private val renderer: TerrainRenderer,
    private val storage: RenderChunkStorage,
    @JvmField val index: Int
) {
    var originX = 0; private set
    val originY get() = storage.minY shl 4
    var originZ = 0; private set

    @JvmField
    val frustumCull: FrustumCull = FrustumCullImpl()

    @JvmField
    val layerBatchArray = Array(renderer.layerCount) { LayerBatch() }

    @JvmField
    val sortSuppArray = arrayOfNulls<RenderChunk>(storage.regionChunkCount)

    @Suppress("UNCHECKED_CAST")
    @JvmField
    val visibleRenderChunkList = FastObjectArrayList.wrap(arrayOfNulls<RenderChunk>(storage.regionChunkCount), 0)

    @JvmField
    val tempVisibleBits = IntArray(storage.regionChunkCount)

    @JvmField
    val vertexBufferPool = RenderBufferPool((4 * 1024 * 1024).countTrailingZeroBits())

    @JvmField
    val indexBufferPool = RenderBufferPool((4 * 1024 * 1024).countTrailingZeroBits())

    private var vbo = vertexBufferPool.bufferObject
    private var ibo = indexBufferPool.bufferObject

    @JvmField
    var vao = VertexArrayObject().apply {
        attachVbo(vbo, TerrainRenderer.VERTEX_ATTRIBUTE)
        attachIbo(ibo)
    }

    fun updateVao() {
        val newVbo = vertexBufferPool.bufferObject
        val newIbo = indexBufferPool.bufferObject
        if (vbo !== newVbo || ibo !== newIbo) {
            vbo = newVbo
            ibo = newIbo

            vao.destroyVao()
            vao = VertexArrayObject().apply {
                attachVbo(newVbo, TerrainRenderer.VERTEX_ATTRIBUTE)
                attachIbo(newIbo)
            }
        }
    }

    inline fun getLayer(index: Int): LayerBatch {
        return layerBatchArray[index]
    }

    fun setPos(x: Int, z: Int) {
        if (x != originX || z != originZ) {
            originX = x
            originZ = z
            frustumCull.reset()
        }
    }

    fun destroy() {
        vao.destroyVao()
        vertexBufferPool.destroy()
        indexBufferPool.destroy()
        for (layer in layerBatchArray) {
            layer.destroy()
        }
    }

    class LayerBatch {
        private val serverBuffer = BufferObject.Immutable().apply {
            allocate(4096 * 63 * 20, GL_DYNAMIC_STORAGE_BIT)
        }

        private val clientBuffer by lazy { clientBufferPool.get() }
        private var index = 0
        private var isDirty = false

        val bufferID get() = serverBuffer.id
        var count = 0; private set
        val isEmpty get() = count == 0

        fun update() {
            index = 0
            isDirty = true
            count = 0
        }

        fun put(vertexOffset: Int, indexOffset: Int, indexCount: Int, baseInstance: Int) {
            val address = clientBuffer.address + index

            UNSAFE.putInt(address, indexCount / 4)
            UNSAFE.putInt(address + 4L, 1)
            UNSAFE.putInt(address + 8L, indexOffset / 4)
            UNSAFE.putInt(address + 12L, vertexOffset / 16)
            UNSAFE.putInt(address + 16L, baseInstance)

            index += 20
            this.count++
        }

        fun checkUpdate() {
            if (isDirty && count != 0) {
                clientBuffer.limit(index)
                glInvalidateBufferData(serverBuffer.id)
                glNamedBufferSubData(serverBuffer.id, 0L, clientBuffer)
            }
            isDirty = false
        }

        fun destroy() {
            serverBuffer.destroy()
            clientBufferPool.put(clientBuffer)
        }

        private companion object {
            private val clientBufferPool = SoftReferenceObjectPool { allocateByte(4096 * 63 * 20) }

            @JvmField
            val UNSAFE = run {
                val field = Unsafe::class.java.getDeclaredField("theUnsafe")
                field.isAccessible = true
                field.get(null) as Unsafe
            }

            @JvmField
            val ADDRESS_OFFSET = UNSAFE.objectFieldOffset(Buffer::class.java.getDeclaredField("address"))

            inline val ByteBuffer.address
                get() = UNSAFE.getLong(this, ADDRESS_OFFSET)
        }
    }

    private inner class FrustumCullImpl : FrustumCull(renderer) {
        override fun isInFrustum(frustum: FrustumIntersection): Boolean {
            val x = (originX - renderer.renderPosX).toFloat()
            val y = (originY - renderer.renderPosY).toFloat()
            val z = (originZ - renderer.renderPosZ).toFloat()
            return frustum.testAab(x, y, z, x + 256.0f, y + (storage.sizeY shl 4), z + 256.0f)
        }
    }
}