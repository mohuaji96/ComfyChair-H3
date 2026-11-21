import io
p = "app/src/test/java/sh/hnet/comfychair/BreastsCoverageTest.kt"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''    @Test
    fun aPlainTopStillHidesShape() {
        val filled = fillRate(
            "衣冠不整",''', '''    @Test
    fun aPlainTopStillHidesShape() {
        val filled = fillRate(
            "只穿上衣",''')
s = s.replace('''    @Test
    fun anExposedChestKeepsOnlyTheSize() {''', '''    @Test
    fun aDishevelledLookCountsAsOpen() {
        assertTrue("衣冠不整时衣服已经没遮住", fillRate("衣冠不整", "shape") >= 18)
        assertTrue("衣装半褪时衣服已经没遮住", fillRate("衣装半褪", "shape") >= 18)
    }

    @Test
    fun anExposedChestKeepsOnlyTheSize() {''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
