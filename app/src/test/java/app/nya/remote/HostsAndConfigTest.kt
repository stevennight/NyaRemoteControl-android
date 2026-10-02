package app.nya.remote

import android.content.SharedPreferences
import app.nya.remote.data.ConfigStore
import app.nya.remote.data.ConnSettings
import app.nya.remote.data.ControlMode
import app.nya.remote.data.HostBook
import app.nya.remote.ui.ago
import app.nya.remote.ui.hue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host names and per-host settings, as the Windows client's config.rs tests them; settings of 0.1.x. */
class HostsAndConfigTest {
    private fun HostBook.connect(address: String, name: String, fp: String, now: Long) = connected(address, name, fp, fp.take(4), now).first

    @Test
    fun namesFollowTheHostUnlessRenamed() {
        var b = HostBook(emptyList())
        b = b.connect("10.0.0.1", "pc", "aa", 1)
        assertEquals("pc", b.byAddress("10.0.0.1")!!.name)
        b = b.connect("10.0.0.2", "pc", "bb", 1)
        assertEquals("pc (2)", b.byAddress("10.0.0.2")!!.name)
        // The host renames itself: followed.
        b = b.connect("10.0.0.1", "office-pc", "aa", 2)
        assertEquals("office-pc", b.byAddress("10.0.0.1")!!.name)
        // Renamed here: kept across connections.
        val id = b.byAddress("10.0.0.1")!!.id
        b = b.rename(id, " 公司 ")
        b = b.connect("10.0.0.1", "other", "ab", 3)
        assertEquals("公司", b.byAddress("10.0.0.1")!!.name)
        assertEquals("ab", b.byAddress("10.0.0.1")!!.fingerprint)
        assertTrue(runCatching { b.rename(id, "pc (2)") }.isFailure)
        assertTrue(runCatching { b.rename(b.byAddress("10.0.0.2")!!.id, "10.0.0.1") }.isFailure)
        // An empty name goes back to the host's own.
        b = b.rename(id, "  ")
        assertEquals("other", b.byAddress("10.0.0.1")!!.name)
        assertFalse(b.byAddress("10.0.0.1")!!.customName)
        // Added by hand without a name: shows the address until connected.
        b = b.add("10.0.0.3", "")
        assertEquals("10.0.0.3", b.byAddress("10.0.0.3")!!.name)
        b = b.connect("10.0.0.3", "nas", "cc", 4)
        assertEquals("nas", b.byAddress("10.0.0.3")!!.name)
        b = b.add("10.0.0.4", "家里")
        b = b.connect("10.0.0.4", "nas", "dd", 4)
        assertEquals("家里", b.byAddress("10.0.0.4")!!.name)
        assertTrue("the same address twice", runCatching { b.add("10.0.0.4", "") }.isFailure)
        assertEquals(4L, b.byAddress("10.0.0.4")!!.lastConnected)
    }

    @Test
    fun perHostSettings() {
        val defaults = ConnSettings()
        var b = HostBook(emptyList()).connect("10.0.0.1", "pc", "aa", 1)
        assertFalse(b.settingsFor("10.0.0.1", defaults).mic)
        assertTrue("no own settings: the defaults", b.settingsFor("10.0.0.1", defaults.copy(mic = true)).mic)
        b = b.editSettings("10.0.0.1", defaults.copy(mic = true)) { it.copy(mode = "game") }
        val d = b.settingsFor("10.0.0.1", defaults)
        assertEquals("own settings start from the defaults", "game" to true, d.mode to d.mic)
        assertEquals("office", b.settingsFor("10.0.0.9", defaults).mode)
        assertEquals(b, b.editSettings("10.0.0.9", defaults) { it })
        b = b.setSettings(b.byAddress("10.0.0.1")!!.id, null)
        assertNull(b.byAddress("10.0.0.1")!!.settings)
    }

    @Test
    fun settingsOf01xAreTakenOver() {
        val old = MemPrefs(
            mapOf(
                "resolution" to "HOST", "gameMode" to true, "maxFps" to 90, "codec" to "hevc", "audio" to false,
                "physicalOff" to true, "controlMode" to "MOUSE", "syncClipboard" to false, "showStats" to true, "pcKeyboard" to true,
                "shares" to """[{"name":"DCIM","path":"/storage/emulated/0/DCIM","readOnly":true}]""",
            ),
        )
        val c = ConfigStore(old).load()
        val d = c.defaults
        assertEquals(0, d.vdCount)
        assertFalse("no virtual screen: physical displays stay on", d.physicalOff)
        assertEquals("game", d.mode)
        assertEquals(90, d.maxFps)
        assertEquals("hevc", d.codec)
        assertEquals(ControlMode.MOUSE, d.controlMode)
        assertFalse(d.audio || d.clipboard)
        assertTrue(c.showStats && d.pcKeyboard)
        assertEquals("DCIM", d.sharedFolders.single().name)
        // Saved in the new form and read back the same.
        val store = ConfigStore(old)
        store.save(c)
        assertEquals(c, store.load())
        assertEquals("screen1080", ConfigStore(MemPrefs(mapOf("resolution" to "SCREEN_1080"))).load().defaults.vdSize)
        assertEquals(ConnSettings(), ConfigStore(MemPrefs(emptyMap())).load().defaults)
    }

    @Test
    fun agoAndHue() {
        val now = 1_000_000_000L
        assertEquals("从未连接", ago(0, now))
        assertEquals("刚刚", ago(now - 5_000, now))
        assertEquals("3 分钟前", ago(now - 180_000, now))
        assertEquals("2 小时前", ago(now - 7_200_000, now))
        // The web page's hash: h = 7; h = (h * 31 + code) >>> 0 per character; h % 360.
        assertEquals(((7L * 31 + 'p'.code) * 31 + 'c'.code) % 360, hue("pc").toLong())
        assertTrue(hue("公司") in 0f..359f)
    }
}

/** SharedPreferences in memory. */
private class MemPrefs(initial: Map<String, Any?>) : SharedPreferences {
    private val m = initial.toMutableMap()
    override fun getAll(): MutableMap<String, *> = m
    override fun getString(key: String, defValue: String?) = m[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = m[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = m[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = m[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = m[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in m
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { m.clear() }
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            pending.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        }
    }
}
