package me.luna.fastmc.shared.opengl.impl

import me.luna.fastmc.shared.opengl.*
import me.luna.fastmc.shared.terrain.ChunkBuilderTask
import me.luna.fastmc.shared.util.ConcurrentObjectPool
import me.luna.fastmc.shared.util.collection.AtomicByteArray
import me.luna.fastmc.shared.util.collection.FastObjectArrayList
import me.luna.fastmc.shared.util.pollEach
import sun.misc.Unsafe
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MappedBufferPool(sectorSizePower: Int, private val sectorCapacity: Int, val maxRegions: Int) {
    private val sectorSize = 1 shl sectorSizePower
    val capacity = sectorSize * sectorCapacity

    private val vbo = BufferObject.Immutable().apply {
        allocate(capacity, GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT)
    }

    private val baseBuffer = glMapNamedBufferRange(
        vbo.id,
        0,
        capacity.toLong(),
        GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT or GL_MAP_UNSYNCHRONIZED_BIT
    )!!
    private val baseAddress = baseBuffer.address

    private val regionPool = ConcurrentObjectPool { Region() }
    private val sectorState = AtomicByteArray(sectorCapacity)
    private val usedSectorCount0 = AtomicInteger(0)
    private val unusedRegionCount0 = AtomicInteger(maxRegions)

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private val pendingRelease = ConcurrentLinkedQueue<Region>()
    private val releaseTaskQueue = java.util.ArrayDeque<ReleaseTask>()

    val allocatedSize get() = usedSectorCount0.get() * sectorSize
    val allocatedRegion get() = maxRegions - unusedRegionCount0.get()

    fun update() {
        val head = releaseTaskQueue.peekFirst()
        if (head != null && head.tryRelease()) {
            releaseTaskQueue.pollFirst()
            doNotify()
        }

        var releaseTask: ReleaseTask? = null
        pendingRelease.pollEach {
            if (releaseTask == null) {
                releaseTask = ReleaseTask()
            }
            releaseTask!!.list.add(it)
        }
        if (releaseTask != null) {
            releaseTaskQueue.offerLast(releaseTask)
        }
    }

    private fun doNotify() {
        lock.withLock {
            condition.signalAll()
        }
    }

    fun allocate(task: ChunkBuilderTask): Region {
        while (!checkCounter()) {
            //
        }

        if (sectorState.compareAndSet(0, FALSE, TRUE)) {
            usedSectorCount0.incrementAndGet()
            return regionPool.get().init(0)
        }

        while (true) {
            var block = sectorCapacity / 2
            while (block > 0) {
                var i = block
                while (i < sectorCapacity) {
                    if (sectorState.compareAndSet(i, FALSE, TRUE)) {
                        usedSectorCount0.incrementAndGet()
                        return regionPool.get().init(i)
                    }
                    i += block
                }
                block /= 2
            }

            if (lock.withLock { condition.awaitNanos(5_000_000L) } <= 0) {
                task.checkCancelled()
            }
        }
    }

    fun destroy() {
        vbo.destroy()
    }

    private fun checkCounter(): Boolean {
        var prev: Int
        do {
            prev = unusedRegionCount0.get()
            if (prev == 0) return false
        } while (!unusedRegionCount0.compareAndSet(prev, prev - 1))
        return true
    }

    private fun release(region: Region) {
        for (i in region.sectorOffset until region.sectorEnd) {
            sectorState.set(i, FALSE)
            usedSectorCount0.decrementAndGet()
        }
        regionPool.put(region)
        unusedRegionCount0.incrementAndGet()
    }

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append('[')
        for (i in 0 until sectorCapacity) {
            stringBuilder.append(sectorState.get(i))
            if (i != sectorCapacity - 1) {
                stringBuilder.append(',')
            }
        }
        stringBuilder.append(']')
        return stringBuilder.toString()
    }

    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    inner class Region internal constructor() {
        val vboID get() = vbo.id

        val buffer = baseBuffer.duplicate().order(ByteOrder.nativeOrder())!!
        private val swapBuffer = baseBuffer.duplicate().order(ByteOrder.nativeOrder())!!

        var sectorOffset = -1; internal set
        var sectorLength = -1; internal set
        val sectorEnd get() = sectorOffset + sectorLength

        var address = buffer.address
        val offset get() = sectorOffset * sectorSize
        val length get() = sectorLength * sectorSize

        fun init(start: Int): Region {
            this.sectorOffset = start
            sectorLength = 1

            buffer.address = baseAddress + offset
            this.address = buffer.address

            buffer.capacity = length
            buffer.clear()
            return this
        }

        fun expand(task: ChunkBuilderTask) {
            while (true) {
                if (sectorEnd < sectorCapacity) {
                    if (sectorState.compareAndSet(sectorEnd, FALSE, TRUE)) {
                        usedSectorCount0.incrementAndGet()
                        sectorLength++
                        buffer.capacity = length
                        buffer.limit(buffer.capacity)
                        return
                    }
                }

                var block = sectorCapacity / 2
                while (block > 0) {
                    var i = block
                    while (i < sectorCapacity - sectorLength) {
                        run label@{
                            for (i1 in i until i + sectorLength + 1) {
                                if (sectorState.get(i1) == TRUE) {
                                    return@label
                                }
                            }

                            var allocated = 0
                            while (true) {
                                if (sectorState.compareAndSet(i + allocated, FALSE, TRUE)) {
                                    if (++allocated > sectorLength) {
                                        swapBuffer.address = buffer.address
                                        swapBuffer.position = 0
                                        swapBuffer.limit = buffer.position
                                        swapBuffer.capacity = buffer.capacity

                                        buffer.address = baseAddress + i * sectorSize
                                        this.address = buffer.address

                                        buffer.capacity = allocated * sectorSize
                                        buffer.clear()
                                        address = buffer.address

                                        buffer.put(swapBuffer)

                                        for (i1 in sectorOffset until sectorEnd) {
                                            sectorState.set(i1, FALSE)
                                        }
                                        doNotify()

                                        sectorOffset = i
                                        sectorLength = allocated
                                        usedSectorCount0.incrementAndGet()

                                        return
                                    }
                                } else if (allocated != 0) {
                                    for (i1 in i until i + allocated) {
                                        sectorState.set(i1, FALSE)
                                    }
                                    doNotify()
                                    return@label
                                }

                                if (lock.withLock { condition.awaitNanos(1_000_000L) } <= 0) {
                                    task.checkCancelled()
                                }
                            }
                        }

                        i += block
                    }
                    block /= 2
                }

                if (lock.withLock { condition.awaitNanos(1_000_000L) } <= 0) {
                    task.checkCancelled()
                }
            }
        }

        fun release() {
            pendingRelease.add(this)
        }
    }

    private inner class ReleaseTask {
        val list = FastObjectArrayList<Region>()
        private val sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        fun tryRelease(): Boolean {
            return if (glGetSynciv(sync, GL_SYNC_STATUS) == GL_SIGNALED) {
                glDeleteSync(sync)
                for (i in list.indices) {
                    release(list[i])
                }
                true
            } else {
                false
            }
        }
    }

    private companion object {
        const val FALSE: Byte = 0
        const val TRUE: Byte = 1

        @JvmField
        val UNSAFE = run {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        }

        @JvmField
        val ADDRESS_OFFSET = UNSAFE.objectFieldOffset(Buffer::class.java.getDeclaredField("address"))

        @JvmField
        val POSITION_OFFSET = UNSAFE.objectFieldOffset(Buffer::class.java.getDeclaredField("position"))

        @JvmField
        val LIMIT_OFFSET = UNSAFE.objectFieldOffset(Buffer::class.java.getDeclaredField("limit"))

        @JvmField
        val CAPACITY_OFFSET = UNSAFE.objectFieldOffset(Buffer::class.java.getDeclaredField("capacity"))

        inline var ByteBuffer.address
            get() = UNSAFE.getLong(this, ADDRESS_OFFSET)
            set(value) {
                UNSAFE.putLong(this, ADDRESS_OFFSET, value)
            }

        inline var ByteBuffer.position
            get() = position()
            set(value) {
                UNSAFE.putInt(this, POSITION_OFFSET, value)
            }

        inline var ByteBuffer.limit
            get() = position()
            set(value) {
                UNSAFE.putInt(this, LIMIT_OFFSET, value)
            }

        inline var ByteBuffer.capacity
            get() = capacity()
            set(value) {
                UNSAFE.putInt(this, CAPACITY_OFFSET, value)
            }
    }
}