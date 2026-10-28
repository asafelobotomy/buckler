package dev.solon.bucklerhome

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMapTest {
    @Test
    fun actionsRoundTripThroughTheirCodes() {
        (RemoteAction.FIXED_CHOICES + RemoteAction.Default).forEach { assertEquals(it, RemoteAction.parse(it.code)) }
        assertEquals(RemoteAction.OpenApp("org.videolan.vlc", "VLC"), RemoteAction.parse("app:org.videolan.vlc") { "VLC" })
        assertEquals(RemoteAction.Default, RemoteAction.parse(null))
        assertEquals(RemoteAction.Default, RemoteAction.parse("something-removed"))
    }

    @Test
    fun onlyBucklersButtonsAreCaughtByKey() {
        assertEquals(RemoteButton.NETFLIX, RemoteButton.forKey(KeyEvent.KEYCODE_BUTTON_12))
        assertEquals(RemoteButton.BACK, RemoteButton.forKey(KeyEvent.KEYCODE_BACK))
        assertNull(RemoteButton.forKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(RemoteButton.forKey(KeyEvent.KEYCODE_MENU))
        assertNull(RemoteButton.forKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun everyFixedButtonSaysWhy() {
        RemoteButton.entries.filter { it.mapping == RemoteButton.Mapping.FIXED }.forEach {
            assert(!it.fixedReason.isNullOrBlank()) { "${it.name} needs a reason" }
        }
    }

    @Test
    fun remotesAreKnownByNvidiasProductIds() {
        assertEquals(RemoteModel.SHIELD_2019, RemoteModel.forProduct(0x0955, 0x7217))
        assertEquals(RemoteModel.SHIELD_2017, RemoteModel.forProduct(0x0955, 0x7213))
        assertEquals(RemoteModel.SHIELD_2015, RemoteModel.forProduct(0x0955, 0x7212))
        assertNull(RemoteModel.forProduct(0x0955, 0x7214)) // the 2017 game controller
        assertNull(RemoteModel.forProduct(0x18d1, 0x7217))
    }

    @Test
    fun eachDrawingPlacesEveryButtonOnce() {
        RemoteModel.entries.forEach { model ->
            val buttons = model.layout.map { it.button }
            assertEquals(model.name, buttons.size, buttons.toSet().size)
            if (model.drawn) {
                model.layout.forEach {
                    assert(it.x in 0..model.bodyWidth && it.y in 0..model.bodyHeight) { "${model.name} ${it.button} off the body" }
                }
            }
        }
        assert(RemoteModel.SHIELD_2019.triangular && RemoteModel.entries.count { it.triangular } == 1)
    }
}
