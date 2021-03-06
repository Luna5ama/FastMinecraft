package me.luna.fastmc.shared.texture

import me.luna.fastmc.shared.opengl.IGLBinding
import me.luna.fastmc.shared.opengl.IGLObject
import me.luna.fastmc.shared.opengl.glBindTexture
import me.luna.fastmc.shared.resource.Resource

interface ITexture : Resource, IGLObject, IGLBinding {
    override fun bind() {
        glBindTexture(id)
    }

    override fun unbind() {
        glBindTexture(0)
    }
}