package sh.hnet.comfychair.storage

import java.io.File

/**
 * 一组文件一起换：先把每一份都写在临时文件里，确认都写成了，再挨个换上去。
 *
 * 导入一个词库要动好几份文件（工作副本、基线、已导入标记）。以前是一份一份写，
 * 第二份失败时第一份已经生效了——库里留下半套状态，下次启动按错误的基线做三方合并。
 * 这里任一步失败就把已经换掉的逐个退回去，用户看到的只有「导入失败」，不会留下半套。
 */
internal object AtomicSwap {

    /**
     * @param steps 必须是「临时文件 to 目标文件」，临时文件已经写好内容。
     *   顺序即换入顺序，回退时按相反顺序来。
     */
    fun commit(steps: List<Pair<File, File>>) {
        val swapped = mutableListOf<Pair<File, File>>() // 旧副本 to 目标
        try {
            for ((temp, target) in steps) {
                if (target.exists()) {
                    val old = File(target.parentFile, "${target.name}.old")
                    target.copyTo(old, overwrite = true)
                    swapped += old to target
                }
                move(temp, target)
            }
        } catch (error: Throwable) {
            swapped.asReversed().forEach { (old, target) ->
                runCatching { old.copyTo(target, overwrite = true) }
            }
            throw error
        } finally {
            steps.forEach { (temp, _) -> if (temp.exists()) temp.delete() }
            swapped.forEach { (old, _) -> if (old.exists()) old.delete() }
        }
    }

    /** 覆盖式改名：新的一份没落位之前，旧的那份一直在原地。 */
    fun move(temp: File, target: File) {
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
