package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretCodecTest {

    private fun testKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    @Test
    fun `seal and open round trips plaintext correctly`() {
        val key = testKey()
        val codec = SecretCodec { key }

        val sealed = codec.seal("password", "MySecretP@ssw0rd")
        org.junit.Assert.assertNotNull(sealed)

        val opened = codec.open("password", sealed!!)
        assertEquals("MySecretP@ssw0rd", opened)
    }

    @Test
    fun `opening under different name fails due to AAD mismatch`() {
        val key = testKey()
        val codec = SecretCodec { key }

        val sealed = codec.seal("password_1", "SecretValue")
        org.junit.Assert.assertNotNull(sealed)

        val opened = codec.open("password_2", sealed!!)
        assertNull(opened)
    }
}
