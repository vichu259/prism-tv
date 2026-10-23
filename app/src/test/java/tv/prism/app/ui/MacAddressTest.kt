package tv.prism.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAddressTest {
    @Test
    fun insertsColonsAndUppercases() {
        assertEquals("00:1A:79:AB:CD:EF", MacAddress.format("001a79abcdef"))
        assertEquals("00:1A:7", MacAddress.format("001A7"))
        assertEquals("00:1A:79:", MacAddress.format("00:1A:79:"))
    }

    @Test
    fun dropsJunkAndExtraCharacters() {
        assertEquals("00:1A:79:AB:CD:EF", MacAddress.format("00-1a-79-ab-cd-ef-99"))
        assertEquals("00:1A", MacAddress.format("0 0 x 1 a"))
    }

    @Test
    fun validatesCompleteAddressesOnly() {
        assertTrue(MacAddress.isValid("00:1A:79:12:34:56"))
        assertFalse(MacAddress.isValid("00:1A:79:12:34"))
        assertFalse(MacAddress.isValid("00:1A:79:"))
    }
}
