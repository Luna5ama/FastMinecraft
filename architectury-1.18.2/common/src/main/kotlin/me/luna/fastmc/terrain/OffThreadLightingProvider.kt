package me.luna.fastmc.terrain

import me.luna.fastmc.shared.util.FastMcExtendScope
import me.luna.fastmc.shared.util.isDoneOrNull
import me.luna.fastmc.shared.util.threadGroupMain
import me.luna.fastmc.util.hasPendingUpdates
import me.luna.fastmc.util.lightStorage
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.ChunkSectionPos
import net.minecraft.world.LightType
import net.minecraft.world.chunk.ChunkNibbleArray
import net.minecraft.world.chunk.ChunkProvider
import net.minecraft.world.chunk.light.ChunkLightProvider
import net.minecraft.world.chunk.light.LightingProvider
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class OffThreadLightingProvider(
    chunkProvider: ChunkProvider,
    hasBlockLight: Boolean, hasSkyLight: Boolean
) : LightingProvider(chunkProvider, hasBlockLight, hasSkyLight) {
    @JvmField
    val readWriteLock = ReentrantReadWriteLock()

    private var lastLightUpdateFuture: Future<*>? = null

    override fun doLightUpdates(maxUpdateCount: Int, doSkylight: Boolean, skipEdgeLightPropagation: Boolean): Int {
        throw UnsupportedOperationException()
    }

    override fun setSectionStatus(pos: ChunkSectionPos, notReady: Boolean) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.setSectionStatus(pos, notReady)
            }
        }
    }

    override fun setSectionStatus(pos: BlockPos, notReady: Boolean) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.setSectionStatus(pos, notReady)
            }
        }
    }

    override fun checkBlock(pos: BlockPos?) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.checkBlock(pos)
            }
        }
    }

    override fun addLightSource(pos: BlockPos, level: Int) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.addLightSource(pos, level)
            }
        }
    }

    override fun setColumnEnabled(pos: ChunkPos, lightEnabled: Boolean) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.setColumnEnabled(pos, lightEnabled)
            }
        }
    }

    override fun enqueueSectionData(
        lightType: LightType?,
        pos: ChunkSectionPos?,
        nibbles: ChunkNibbleArray?,
        bl: Boolean
    ) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.enqueueSectionData(lightType, pos, nibbles, bl)
            }
        }
    }

    override fun setRetainData(pos: ChunkPos, retainData: Boolean) {
        executor.execute {
            readWriteLock.writeLock().withLock {
                super.setRetainData(pos, retainData)
            }
        }
    }

    fun doLightUpdates(doSkylight: Boolean) {
        if (lastLightUpdateFuture.isDoneOrNull) {
            val hasBlockLightUpdate = hasPendingUpdate(blockLightProvider)
            val hasSkyLightUpdate = hasPendingUpdate(skyLightProvider)
            if (!hasBlockLightUpdate && !hasSkyLightUpdate) return

            lastLightUpdateFuture = executor.submit {
                val writeLock = readWriteLock.writeLock()
                writeLock.withLock {
                    val blockLightUpdate = if (hasBlockLightUpdate) {
                        FastMcExtendScope.pool.submit {
                            blockLightProvider?.doLightUpdates(Int.MAX_VALUE, doSkylight, true)
                        }
                    } else {
                        null
                    }
                    val skyLightUpdate = if (hasSkyLightUpdate) {
                        FastMcExtendScope.pool.submit {
                            skyLightProvider?.doLightUpdates(Int.MAX_VALUE, doSkylight, true)
                        }
                    } else {
                        null
                    }

                    blockLightUpdate?.get()
                    skyLightUpdate?.get()
                }
            }
        }
    }

    private fun hasPendingUpdate(provider: ChunkLightProvider<*, *>?): Boolean {
        return provider != null && (provider.hasPendingUpdates || provider.lightStorage.hasPendingUpdates)
    }

    companion object {
        @JvmField
        val executor = ThreadPoolExecutor(
            0,
            1,
            1L,
            TimeUnit.MINUTES,
            LinkedBlockingQueue(),
            ThreadFactory {
                Thread(threadGroupMain, it, "FastMinecraft-Lighting").apply {
                    priority = 3
                }
            }
        )
    }
}