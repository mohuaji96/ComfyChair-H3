package sh.hnet.comfychair.workflow

/**
 * Socket ceilings declared by autogrow inputs of the connected server.
 *
 * `MiniMaxH3ReferenceToVideo.ref_images` is a `COMFY_AUTOGROW_V3` with `min 0 / max 9`, so a
 * fourth reference image is legal *because the node says so*, not because the UI decided it.
 * Nothing is assumed when the server has not been read yet: the map is empty and callers fall
 * back to the slots the workflow itself already defines.
 */
object AutogrowLimits {

    private val limits = mutableMapOf<String, Int>()

    @Synchronized
    fun record(classType: String, input: String, max: Int) {
        limits["$classType|$input"] = max
    }

    @Synchronized
    fun max(classType: String, input: String): Int? = limits["$classType|$input"]

    @Synchronized
    fun clear() = limits.clear()
}
