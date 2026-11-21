import io
p = "app/src/main/java/sh/hnet/comfychair/workflow/StoryGenerator.kt"
s = io.open(p, encoding="utf-8").read()

start = "    private fun formatFemaleParagraph(picks: Map<String, StoryPick>): String {"
end = '        val s1 = "一位$subject" + (if (details.isNotEmpty()) "，" + details.joinToString("，") else "") + "。"'
i = s.index(start)
j = s.index(end)

body = '''    private fun formatFemaleParagraph(picks: Map<String, StoryPick>): String {
        val ethnicity = picks[ETHNICITY]?.value.orEmpty()
        val region = picks[REGION]?.value.orEmpty()
        val age = picks[AGE]?.value.orEmpty()
        val look = picks[LOOK]?.value.orEmpty()
        val skin = picks[SKIN]?.value.orEmpty()
        val build = picks[BUILD]?.value.orEmpty()
        val hair = picks[HAIR]?.value.orEmpty()
        val hairColor = picks[HAIR_COLOR]?.value.orEmpty()
        // 旧存档还没迁移时 bust 里可能还有值，兜底读一次。
        val bustSize = picks[BUST_SIZE]?.value.orEmpty().ifEmpty { picks[BUST]?.value.orEmpty() }
        val bustShape = picks[BUST_SHAPE]?.value.orEmpty()
        val areola = picks[AREOLA]?.value.orEmpty()
        val nipple = picks[NIPPLE]?.value.orEmpty()
        val accent = picks[ACCENT]?.value.orEmpty()

        var race = ethnicity.ifEmpty { "女子" }
        val femaleRaces = setOf("魅魔", "海妖", "狐妖", "猫妖", "狼女", "蛇女", "兔耳娘", "雪女", "花妖", "龙女", "牛娘", "鹿娘", "羊娘", "虎娘", "蛛娘", "精灵", "暗精灵", "半精灵", "妖精", "花精", "吸血鬼", "天使", "堕天使", "女武神", "美人鱼", "妖怪", "亡灵", "幽灵", "机械人形")
        if (race in femaleRaces) race = "女$race"
        else if (race in setOf("人类", "欧美人", "东亚人", "混血长相")) race = "女子"

        // 地域当限定语：「韩国女子」「来自韩国的精灵」。
        val raceWord = when {
            region.isEmpty() -> race
            race == "女子" -> "${region}女子"
            else -> "来自${region}的$race"
        }

        val body = if (build.isNotEmpty()) {
            // 只有纯形容词前面才补「身材」。脖子/腿/腰/臀这些本来就是身体部位，
            // 再补就成了「身材脖子很长」「身材沙漏身材」。
            val partWords = listOf("肩膀", "脖子", "腿", "腰", "臀", "骨架", "身形", "曲线", "罩杯", "肉感", "个子")
            when {
                build.contains("肩膀") -> "身量纤细、$build"
                build.startsWith("身材") || build.startsWith("个子") || build.startsWith("身量") ||
                    build.startsWith("体态") || build.endsWith("身材") ||
                    partWords.any { build.contains(it) } -> build
                else -> "身材$build"
            }
        } else ""

        // 年龄与气质在最前，读起来是「二十八岁、气质冷艳、身材纤细的韩国女子」。
        val lookPart = when {
            look.isEmpty() -> ""
            look.startsWith("气质") -> look
            else -> "气质$look"
        }
        val prefix = listOf(age, lookPart).filter { it.isNotEmpty() }.joinToString("、")
        val subject = when {
            prefix.isNotEmpty() && body.isNotEmpty() -> "$prefix、${body}的$raceWord"
            prefix.isNotEmpty() -> "${prefix}的$raceWord"
            body.isNotEmpty() -> "${body}的$raceWord"
            else -> raceWord
        }

        val details = mutableListOf<String>()
        if (skin.isNotEmpty()) {
            details += when {
                "水珠" in skin || "汗光" in skin || "红晕" in skin || "淡红" in skin -> skin
                "皮" in skin || "肤" in skin -> skin
                else -> "${skin}肌肤"
            }
        }
        // 胸部四格：大小、形态、乳晕、乳头。后两格只在露胸时才会被抽到。
        val bustBits = mutableListOf<String>()
        if (bustSize.isNotEmpty()) {
            bustBits += if (bustSize.endsWith("罩")) "${bustSize}杯" else bustSize
        }
        if (bustShape.isNotEmpty()) {
            bustBits += if ("胸" in bustShape || "乳" in bustShape) bustShape else "胸型$bustShape"
        }
        if (areola.isNotEmpty()) bustBits += areola
        if (nipple.isNotEmpty()) bustBits += nipple
        if (bustBits.isNotEmpty()) details += bustBits.joinToString("、")

        val hairText = when {
            hair.isEmpty() && hairColor.isEmpty() -> ""
            hairColor.isEmpty() -> hair
            hair.isEmpty() -> "${hairColor}头发"
            else -> "$hairColor$hair"
        }
        if (hairText.isNotEmpty()) {
            details += if (hairText.startsWith("留") || hairText.startsWith("扎") ||
                hairText.startsWith("散") || hairText.startsWith("一头")
            ) hairText else "留着一头$hairText"
        }
        val glasses = picks[GLASSES]?.value.orEmpty()
        if (glasses.isNotEmpty()) {
            details += when {
                glasses.startsWith("戴") || glasses.startsWith("没戴") || glasses.startsWith("摘下") ||
                    glasses.startsWith("眼镜") || glasses.startsWith("墨镜") ||
                    glasses.startsWith("一边") || glasses.startsWith("镜片") -> glasses
                else -> "戴着$glasses"
            }
        }
        if (accent.isNotEmpty()) {
            details += when {
                accent.startsWith("戴") -> accent
                accent.startsWith("有") || accent.startsWith("耳后") || accent.startsWith("嘴唇") ||
                    accent.startsWith("指甲") || accent.startsWith("锁骨") -> accent
                library.profile == "sfw" -> accent
                else -> "戴着$accent"
            }
        }

'''

s = s[:i] + body + s[j:]
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
