package sh.hnet.comfychair.workflow

import androidx.annotation.StringRes
import sh.hnet.comfychair.R

/**
 * What the server is doing right now, in words a user can read.
 *
 * ComfyUI only reports "which node id is executing" plus a step counter. The node id means nothing
 * to a person, so it is translated here by node type. The names are kept at four characters so the
 * left half of the progress line never changes width.
 *
 * Only [SAMPLE] has real numbers behind it; everything else is a waiting state, and pretending
 * otherwise would invent a progress that does not exist.
 */
enum class ProgressStage(val label: String, @StringRes val labelRes: Int, val determinate: Boolean) {
    PREPARE("准备中", R.string.h3_stage_prepare, false),
    LOAD_MODEL("加载模型", R.string.h3_stage_load_model, false),
    SAMPLE("采样", R.string.h3_stage_sample, true),
    DECODE("解码画面", R.string.h3_stage_decode, false),
    COMPOSE("合成视频", R.string.h3_stage_compose, false),
    SAVE("写入文件", R.string.h3_stage_save, false),
    UNKNOWN("处理中", R.string.h3_stage_unknown, false);

    companion object {
        /**
         * Node types that carry a visible meaning. Anything not listed lands in [PREPARE]: the bulk
         * of a graph is constants, switches and math nodes that finish in milliseconds, and naming
         * each one would turn the line into a slide show.
         */
        private val LOADERS = setOf(
            "UNETLoader", "UNETLoaderGGUF", "CLIPLoader", "CLIPLoaderGGUF", "DualCLIPLoader",
            "TripleCLIPLoader", "VAELoader", "CheckpointLoader", "CheckpointLoaderSimple",
            "LoraLoader", "LoraLoaderModelOnly", "ControlNetLoader", "UpscaleModelLoader"
        )
        private val SAMPLERS = setOf(
            "KSampler", "KSamplerAdvanced", "SamplerCustom", "SamplerCustomAdvanced"
        )
        private val DECODERS = setOf("VAEDecode", "VAEDecodeAudio", "VAEDecodeTiled")
        private val COMPOSERS = setOf("CreateVideo", "VHS_VideoCombine")
        private val SAVERS = setOf(
            "SaveImage", "SaveVideo", "SaveAnimatedWEBP", "SaveAudio", "PreviewImage"
        )

        /** Stages that legitimately follow one another; used to reject out-of-order flicker. */
        fun of(classType: String?): ProgressStage = when {
            classType.isNullOrEmpty() -> UNKNOWN
            classType in LOADERS -> LOAD_MODEL
            classType in SAMPLERS -> SAMPLE
            classType in DECODERS -> DECODE
            classType in COMPOSERS -> COMPOSE
            classType in SAVERS -> SAVE
            else -> PREPARE
        }
    }
}
