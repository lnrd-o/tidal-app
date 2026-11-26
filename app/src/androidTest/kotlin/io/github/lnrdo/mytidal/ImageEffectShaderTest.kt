package io.github.lnrdo.mytidal

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lnrdo.mytidal.core.effects.DotShape
import io.github.lnrdo.mytidal.core.effects.EffectOptions
import io.github.lnrdo.mytidal.core.effects.ImageStyle
import io.github.lnrdo.mytidal.ui.EffectShaders
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The GPU shaders of the image effects are compiled by the driver of the phone: this builds each of them, with each shape, on a real device. */
@RunWith(AndroidJUnit4::class)
class ImageEffectShaderTest {
    @Test fun everyEffectShaderBuilds() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        for (style in listOf(ImageStyle.DITHER, ImageStyle.HALFTONE, ImageStyle.PIXEL)) for (shape in DotShape.entries) {
            EffectShaders.effect(EffectOptions(style, cellSize = 8, gridAngle = 45, shape = shape), 3f)
            EffectShaders.effect(EffectOptions(style, cellSize = 8, shape = shape, duotone = false), 3f)
        }
    }
}
