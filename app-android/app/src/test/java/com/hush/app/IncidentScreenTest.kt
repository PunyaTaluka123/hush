package com.hush.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SPEC §7.3: the cancel flow, rendered for both PINs, captured at fixed times,
 * must be pixel-identical. (The status-bar clock is outside the app's window.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class IncidentScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Software-renders the whole window (Robolectric native graphics). */
    private fun snapshot(name: String): IntArray {
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(Canvas(bmp)) }
        File("build/reports/incident-frames").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    @Test
    fun bothPinsRenderIdenticalFrames() {
        val w = testWearer(FakeServer(), "screens")
        var run by mutableStateOf(0)
        var haptics = 0
        var finished = 0
        rule.setContent {
            HushTheme {
                key(run) {
                    IncidentScreen(4, check = { w.enterPin(it, "cancel") { r -> r.run() } }, onAcceptedHaptic = { haptics++ }) { finished++ }
                }
            }
        }
        fun frames(pin: String): List<IntArray> {
            rule.mainClock.autoAdvance = false
            pin.forEach { rule.onNodeWithContentDescription(it.toString()).performClick() }
            var t = 0L
            return listOf(0L, 300L, 600L, 600L, 800L, 100L).map { dt ->
                rule.mainClock.advanceTimeBy(dt)
                t += dt
                snapshot("$pin-${t}ms")
            }
        }
        val real = frames("4821")
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { run++ } // fresh cancel screen, same state as the first time
        rule.waitForIdle()
        val second = frames("4822")
        assertEquals(real.size, second.size)
        assertFalse("frames must show the animation (not a blank capture)", real[1].contentEquals(real[3]))
        real.zip(second).forEachIndexed { i, (a, b) -> assertArrayEquals("frame $i differs", a, b) }
        assertEquals("same haptic, same completion, both times", 2 to 2, haptics to finished)
    }
}
