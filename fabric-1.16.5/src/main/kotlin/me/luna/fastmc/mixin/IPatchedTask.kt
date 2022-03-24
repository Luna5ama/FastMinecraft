package me.luna.fastmc.mixin

import me.luna.fastmc.shared.opengl.VboInfo
import me.luna.fastmc.shared.opengl.glNamedBufferSubData
import me.luna.fastmc.terrain.ChunkVertexData
import net.minecraft.client.render.chunk.ChunkBuilder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

interface IPatchedTask {
    val cancelled0: AtomicBoolean
    val chunkBuilder: ChunkBuilder
    val builtChunk: ChunkBuilder.BuiltChunk

    fun updateVertexData(
        dataArray: Array<ChunkVertexData?>,
        index: Int,
        builtOrigin: Long,
        newBuffer: ByteBuffer,
        vertexCount: Int
    ) {
        val vertexSize = newBuffer.remaining()
        val newVboSize = (vertexSize + 4095) shr 12 shl 12
        val vbo = dataArray[index]?.updateVbo(newVboSize) ?: ChunkVertexData.newVbo(newVboSize)

        glNamedBufferSubData(vbo.id, 0, newBuffer)
        dataArray[index] = ChunkVertexData(
            builtOrigin,
            VboInfo(
                vbo,
                vertexCount,
                vertexSize,
                newVboSize
            )
        )
    }
}