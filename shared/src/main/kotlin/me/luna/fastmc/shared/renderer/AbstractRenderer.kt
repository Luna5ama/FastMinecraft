package me.luna.fastmc.shared.renderer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.luna.fastmc.shared.renderbuilder.AbstractRenderBuilder
import me.luna.fastmc.shared.renderbuilder.IInfo
import me.luna.fastmc.shared.util.ClassIDRegistry
import me.luna.fastmc.shared.util.FastMcCoreScope
import me.luna.fastmc.shared.util.ITypeID
import me.luna.fastmc.shared.util.collection.FastIntMap
import kotlin.coroutines.CoroutineContext

abstract class AbstractRenderer<ET : Any>(
    protected val worldRenderer: WorldRenderer,
    val registry: ClassIDRegistry<Any>
) :
    IRenderer by worldRenderer {
    protected val renderEntryMap = FastIntMap<AbstractRenderEntry<ET, *>>()
    protected val renderEntryList = ArrayList<AbstractRenderEntry<ET, *>>()

    protected inline fun <reified E : ET, reified B : AbstractRenderBuilder<out IInfo<*>>> register() {
        val entityClass = E::class.java
        @Suppress("UNCHECKED_CAST")
        if (!renderEntryMap.containsKey((registry as ClassIDRegistry<E>).get(entityClass))) {
            register<E>(RenderEntry(B::class.java))
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected inline fun <reified E : ET> register(renderEntry: AbstractRenderEntry<out ET, out IInfo<E>>) {
        renderEntryMap[(registry as ClassIDRegistry<E>).get(E::class.java)] = renderEntry as AbstractRenderEntry<ET, *>
        renderEntryList.add(renderEntry)
    }

    fun add(list: List<ET>) {
        list.forEach {
            renderEntryMap[(it as ITypeID).typeID]?.add(it)
        }
    }

    fun clear() {
        renderEntryList.forEach {
            it.clear()
        }
    }

    fun hasRenderer(entity: ET): Boolean {
        return renderEntryMap.containsKey((entity as ITypeID).typeID)
    }

    abstract fun onPostTick(mainThreadContext: CoroutineContext, parentScope: CoroutineScope)

    protected suspend fun updateRenderers(mainThreadContext: CoroutineContext, force: Boolean) {
        coroutineScope {
            for (entry in renderEntryList) {
                if (force) entry.markDirty()
                launch(FastMcCoreScope.context) {
                    entry.update(mainThreadContext, this)
                }
            }
        }
    }

    open fun render() {
        renderEntryList.forEach {
            it.render(this)
        }
    }

    fun destroy() {
        renderEntryList.forEach {
            it.destroyRenderer()
        }
    }

    protected abstract inner class AbstractRenderEntry<E : ET, T : IInfo<E>> {
        abstract fun clear()

        abstract fun add(entity: E)

        abstract fun addAll(collection: Collection<E>)

        abstract fun remove(entity: E): Boolean

        abstract fun removeAll(collection: Collection<E>)

        abstract suspend fun update(mainThreadContext: CoroutineContext, parentScope: CoroutineScope)

        abstract fun render(renderer: IRenderer)

        abstract fun destroyRenderer()

        abstract fun markDirty()
    }

    protected inner class RenderEntry<E : ET, T : IInfo<E>>(
        private val builderClass: Class<out AbstractRenderBuilder<in T>>,
    ) : AbstractRenderEntry<E, T>() {
        private var renderer: AbstractRenderBuilder.Renderer? = null
        private val entities = ArrayList<E>()
        private var dirty = false

        override fun clear() {
            if (entities.isNotEmpty()) {
                entities.clear()
                dirty = true
            }
        }

        override fun add(entity: E) {
            entities.add(entity)
            dirty = true
        }

        override fun addAll(collection: Collection<E>) {
            dirty = entities.addAll(collection) || dirty
        }

        override fun remove(entity: E): Boolean {
            val removed = entities.remove(entity)
            dirty = entities.remove(entity) || dirty
            return removed
        }

        override fun removeAll(collection: Collection<E>) {
            @Suppress("ConvertArgumentToSet")
            dirty = entities.removeAll(collection) || dirty
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun update(
            mainThreadContext: CoroutineContext,
            parentScope: CoroutineScope
        ) {
            if (!dirty) return
            if (entities.isEmpty()) {
                withContext(mainThreadContext) {
                    destroyRenderer()
                }
            } else {
                dirty = false
                val builder = builderClass.getDeclaredConstructor().newInstance()

                builder.init(this@AbstractRenderer, entities.size)
                builder.addAll(entities as List<T>)

                withContext(mainThreadContext) {
                    renderer?.destroy()
                    renderer = builder.build()
                }
            }
        }

        override fun render(renderer: IRenderer) {
            this.renderer?.render(renderer)
        }

        override fun destroyRenderer() {
            renderer?.destroy()
            renderer = null
            dirty = true
        }

        override fun markDirty() {
            dirty = true
        }
    }
}