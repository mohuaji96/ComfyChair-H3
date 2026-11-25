package sh.hnet.comfychair.storage

import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSkipRule
import sh.hnet.comfychair.model.TagSlot

/**
 * Folds a shipped library update into the copy the user has been editing.
 *
 * 三路比对：base 是上次随安装包发下来的词库，local 是用户手上的副本，incoming 是新的内置词库。
 * 用户新增的词、删掉的词、加过的排除项都会还原到新词库上；规则表（约束、跳过规则、锚点）直接用新
 * 词库的，因为界面上能改的只有词，没有规则表。
 *
 * base 缺失时（第一次启用自动升级）退化成只做增量：本地多出来的词保留，规则表整套换成新词库。
 * 没有 base 就分不清"用户删掉的词"和"新词库删掉的词"，这时宁可让规则跟着新词库走，也不能让已经
 * 修掉的旧规则借着合并复活。
 */
object LibraryMerge {

    fun fold(base: TagLibrary?, local: TagLibrary, incoming: TagLibrary): TagLibrary {
        val baseById = base?.categories?.associateBy { it.id }.orEmpty()
        val localById = local.categories.associateBy { it.id }
        val categories = mutableListOf<TagCategory>()

        incoming.categories.forEach { target ->
            val known = baseById[target.id]
            val mine = localById[target.id]
            val slots = mutableListOf<TagSlot>()
            target.slots.forEach { slot ->
                slots += foldSlot(
                    base = known?.slots?.firstOrNull { it.id == slot.id },
                    local = mine?.slots?.firstOrNull { it.id == slot.id },
                    incoming = slot,
                    additive = base == null
                )
            }
            // A slot the user added himself has no counterpart anywhere: it survives untouched.
            mine?.slots.orEmpty().forEach { slot ->
                val nobodyShippedIt = base == null || known?.slots?.none { it.id == slot.id } == true
                if (nobodyShippedIt && target.slots.none { it.id == slot.id }) slots += slot
            }
            categories += target.copy(slots = slots)
        }
        // Same for a whole category the user built: nothing to fold it into, so append it.
        local.categories.forEach { category ->
            val nobodyShippedIt = base == null || baseById[category.id] == null
            if (nobodyShippedIt && localById[category.id] != null && categories.none { it.id == category.id }) {
                categories += category
            }
        }
        if (base != null) {
            local.categories.forEach { category ->
                if (baseById[category.id] == null && categories.none { it.id == category.id }) {
                    categories += category
                }
            }
        }

        val left = local.constraints
        val right = incoming.constraints
        val old = base?.constraints
        return incoming.copy(
            name = incoming.name.ifBlank { local.name },
            categories = categories,
            singletons = foldList(base?.singletons, local.singletons, incoming.singletons),
            skipRules = foldList(base?.skipRules, local.skipRules, incoming.skipRules),
            mutex = foldPairs(base?.mutex, local.mutex, incoming.mutex),
            constraints = TagConstraints(
                handCap = right.handCap,
                handCost = foldIntMap(old?.handCost, left.handCost, right.handCost),
                posture = foldMap(old?.posture, left.posture, right.posture),
                place = foldMap(old?.place, left.place, right.place),
                gaze = foldMap(old?.gaze, left.gaze, right.gaze),
                mouthCap = right.mouthCap,
                mouthCost = foldIntMap(old?.mouthCost, left.mouthCost, right.mouthCost),
                headCap = right.headCap,
                headCost = foldIntMap(old?.headCost, left.headCost, right.headCost),
                soloPose = foldList(old?.soloPose, left.soloPose, right.soloPose),
                scenePoses = foldList(old?.scenePoses, left.scenePoses, right.scenePoses),
                hairState = foldList(old?.hairState, left.hairState, right.hairState),
                onePiece = foldList(old?.onePiece, left.onePiece, right.onePiece),
                onePieceBottom = foldList(old?.onePieceBottom, left.onePieceBottom, right.onePieceBottom),
                toyActs = foldListMap(old?.toyActs, left.toyActs, right.toyActs),
                synonyms = foldPairs(old?.synonyms, left.synonyms, right.synonyms),
                excludes = foldPairs(old?.excludes, left.excludes, right.excludes),
                requires = foldListMap(old?.requires, left.requires, right.requires),
                allowedPostures = foldListMap(old?.allowedPostures, left.allowedPostures, right.allowedPostures),
                postureHints = foldListMap(old?.postureHints, left.postureHints, right.postureHints),
                headPlacements = foldListMap(old?.headPlacements, left.headPlacements, right.headPlacements),
                sceneProps = foldListMap(old?.sceneProps, left.sceneProps, right.sceneProps),
                propNeeds = foldListMap(old?.propNeeds, left.propNeeds, right.propNeeds),
                exactPeople = foldList(old?.exactPeople, left.exactPeople, right.exactPeople),
                raceFeatureHead = foldListMap(old?.raceFeatureHead, left.raceFeatureHead, right.raceFeatureHead),
                raceFeatureBody = foldListMap(old?.raceFeatureBody, left.raceFeatureBody, right.raceFeatureBody),
                raceLead = foldMap(old?.raceLead, left.raceLead, right.raceLead),
                raceName = foldMap(old?.raceName, left.raceName, right.raceName),
                people = foldIntMap(old?.people, left.people, right.people),
                peopleByCategory = foldNestedIntMap(
                    old?.peopleByCategory, left.peopleByCategory, right.peopleByCategory
                ),
                wordAliases = foldMap(old?.wordAliases, left.wordAliases, right.wordAliases),
                peopleCount = foldIntMap(old?.peopleCount, left.peopleCount, right.peopleCount),
                placeSupports = foldListMap(old?.placeSupports, left.placeSupports, right.placeSupports),
                poseNeeds = foldListMap(old?.poseNeeds, left.poseNeeds, right.poseNeeds),
                 selfColoured = foldList(old?.selfColoured, left.selfColoured, right.selfColoured),
                 selfPatterned = foldList(old?.selfPatterned, left.selfPatterned, right.selfPatterned),
                 pos = foldMap(old?.pos, left.pos, right.pos),
                 eras = foldMap(old?.eras, left.eras, right.eras),
                 skinByRegion = foldListMap(old?.skinByRegion, left.skinByRegion, right.skinByRegion),
                 maleOnly = foldList(old?.maleOnly, left.maleOnly, right.maleOnly),
                malePeople = foldList(old?.malePeople, left.malePeople, right.malePeople),
                frameHides = foldListMap(old?.frameHides, left.frameHides, right.frameHides),
                frameHidesCategories = foldListMap(
                    old?.frameHidesCategories, left.frameHidesCategories, right.frameHidesCategories
                ),
                regions = foldListMap(old?.regions, left.regions, right.regions),
                categoryRegions = foldListMap(old?.categoryRegions, left.categoryRegions, right.categoryRegions),
                handBlock = foldList(old?.handBlock, left.handBlock, right.handBlock),
                subjects = foldMap(old?.subjects, left.subjects, right.subjects),
                faceZone = foldMap(old?.faceZone, left.faceZone, right.faceZone),
                weight = foldDoubleMap(old?.weight, left.weight, right.weight)
            )
        )
    }

    /**
     * One slot, three ways.
     *
     * Order comes from the new library; the user's own words are appended in the order he had them,
     * and words he deleted stay deleted even when the new file still carries them.
     */
    private fun foldSlot(
        base: TagSlot?,
        local: TagSlot?,
        incoming: TagSlot,
        additive: Boolean
    ): TagSlot {
        val mine = local?.pool.orEmpty()
        val shipped = base?.pool.orEmpty()
        val added = if (base != null) mine.filterNot { it in shipped } else mine.filterNot { it in incoming.pool }
        val removed = if (base != null) shipped.filterNot { it in mine }.toSet() else emptySet()

        val pool = mutableListOf<String>()
        incoming.pool.forEach { if (it !in removed && it !in pool) pool += it }
        added.forEach { if (it !in removed && it !in pool) pool += it }

        val myExcluded = local?.excluded.orEmpty()
        val oldExcluded = base?.excluded.orEmpty()
        val keptExcluded = if (base != null) {
            oldExcluded.filterNot { it in myExcluded }.toSet()
        } else {
            emptySet()
        }
        val excluded = mutableListOf<String>()
        incoming.excluded.forEach { if (it !in keptExcluded && it !in excluded) excluded += it }
        if (!additive) {
            myExcluded.forEach { if (it !in oldExcluded && it !in excluded) excluded += it }
        }
        return incoming.copy(pool = pool, excluded = excluded)
    }

    /** New entries win; anything the user added on top stays; anything he deleted stays deleted. */
    private fun foldMap(
        base: Map<String, String>?,
        local: Map<String, String>,
        incoming: Map<String, String>
    ): Map<String, String> {
        if (base == null) return incoming
        val out = incoming.toMutableMap()
        local.forEach { (key, value) -> if (base[key] != value) out[key] = value }
        base.keys.forEach { key -> if (key !in local) out.remove(key) }
        return out
    }

    private fun foldIntMap(
        base: Map<String, Int>?,
        local: Map<String, Int>,
        incoming: Map<String, Int>
    ): Map<String, Int> {
        if (base == null) return incoming
        val out = incoming.toMutableMap()
        local.forEach { (key, value) -> if (base[key] != value) out[key] = value }
        base.keys.forEach { key -> if (key !in local) out.remove(key) }
        return out
    }

    private fun foldDoubleMap(
        base: Map<String, Double>?,
        local: Map<String, Double>,
        incoming: Map<String, Double>
    ): Map<String, Double> {
        if (base == null) return incoming
        val out = incoming.toMutableMap()
        local.forEach { (key, value) -> if (base[key] != value) out[key] = value }
        base.keys.forEach { key -> if (key !in local) out.remove(key) }
        return out
    }

    private fun foldListMap(
        base: Map<String, List<String>>?,
        local: Map<String, List<String>>,
        incoming: Map<String, List<String>>
    ): Map<String, List<String>> {
        if (base == null) return incoming
        val out = incoming.toMutableMap()
        local.forEach { (key, value) -> if (base[key] != value) out[key] = value }
        base.keys.forEach { key -> if (key !in local) out.remove(key) }
        return out
    }

    private fun foldNestedIntMap(
        base: Map<String, Map<String, Int>>?,
        local: Map<String, Map<String, Int>>,
        incoming: Map<String, Map<String, Int>>
    ): Map<String, Map<String, Int>> {
        if (base == null) return incoming
        val out = incoming.toMutableMap()
        local.forEach { (key, value) -> if (base[key] != value) out[key] = value }
        base.keys.forEach { key -> if (key !in local) out.remove(key) }
        return out
    }

    private fun <T> foldList(base: List<T>?, local: List<T>, incoming: List<T>): List<T> {
        if (base == null) return incoming
        val added = local.filterNot { it in base }
        val removed = base.filterNot { it in local }.toSet()
        val out = mutableListOf<T>()
        incoming.forEach { if (it !in removed && it !in out) out += it }
        added.forEach { if (it !in removed && it !in out) out += it }
        return out
    }

    /** Pairs and groups: identity is the set of members, so a reordered line still counts as the same. */
    private fun foldPairs(
        base: List<List<String>>?,
        local: List<List<String>>,
        incoming: List<List<String>>
    ): List<List<String>> {
        if (base == null) return incoming
        fun key(list: List<String>) = list.toSet()
        val baseKeys = base.map(::key)
        val localKeys = local.map(::key)
        val added = localKeys.filterNot { it in baseKeys }
        val removed = baseKeys.filterNot { it in localKeys }.toSet()
        val out = mutableListOf<List<String>>()
        val seen = mutableSetOf<Set<String>>()
        incoming.forEach { if (key(it) !in removed && seen.add(key(it))) out += it }
        local.forEachIndexed { index, pair ->
            if (localKeys[index] in added && localKeys[index] !in removed && seen.add(localKeys[index])) out += pair
        }
        return out
    }
}
