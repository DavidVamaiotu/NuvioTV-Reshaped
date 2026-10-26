package com.nuvio.tv.ui.screens.player.seekpreview.colourmatch
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PreviewColourMathTest {
    private fun pattern(w: Int = 128, h: Int = 72, transform: (Double, Double, Double) -> DoubleArray = { r,g,b -> doubleArrayOf(r,g,b) }): PreviewColourPixels {
        val random=java.util.Random(123)
        val colors=Array(32*18){DoubleArray(3){.15+random.nextDouble()*.65}}
        return PreviewColourPixels(w,h,IntArray(w*h){i->
            val c=colors[((i/w)*18/h)*32+((i%w)*32/w)]
            val p=transform(c[0],c[1],c[2])
            -0x1000000 or ((p[0].coerceIn(0.0,1.0)*255).toInt() shl 16) or
                ((p[1].coerceIn(0.0,1.0)*255).toInt() shl 8) or (p[2].coerceIn(0.0,1.0)*255).toInt()
        })
    }
    @Test fun identicalPicturesNeedNoFilter() {
        val p=pattern()
        assertTrue(PreviewColourMath.estimate(p,p)!!.isIdentity)
    }
    @Test fun matchedGradeProducesSmallUsefulAdjustment() {
        val target=pattern { r,g,b ->
            val y=.2126*r+.7152*g+.0722*b
            doubleArrayOf(r,g,b).map {1.08*(y+1.15*(it-y))+.012}.toDoubleArray()
        }
        val c=requireNotNull(PreviewColourMath.estimate(pattern(),target))
        assertFalse(c.isIdentity)
        assertTrue(c.contrast>1f && c.contrast<=1.09f)
        assertTrue(c.saturation>1f && c.saturation<=1.1876f)
    }
    @Test fun tintCanBeCorrectedWithoutChangingAlpha() {
        val target=pattern {r,g,b->doubleArrayOf(r+.02,g-.008,b-.014)}
        val c=requireNotNull(PreviewColourMath.estimate(pattern(),target))
        assertTrue(c.redOffset>c.blueOffset)
        val matrix=c.matrixValues()
        assertArrayEquals(floatArrayOf(0f,0f,0f,1f,0f),matrix.copyOfRange(15,20),0f)
        assertTrue(matrix.all {it.isFinite()})
    }
    @Test fun samePaletteDifferentSceneIsRejected() {
        val p=pattern()
        val flipped=PreviewColourPixels(p.width,p.height,IntArray(p.argb.size){i->
            p.argb[(i/p.width)*p.width+(p.width-1-i%p.width)]
        })
        assertNull(PreviewColourMath.estimate(p,flipped))
    }
    @Test fun darkFlatAndMonochromeFramesDoNotCalibrate() {
        for(color in intArrayOf(0xff020202.toInt(),0xff777777.toInt(),0xffffffff.toInt())) {
            val flat=PreviewColourPixels(128,72,IntArray(128*72){color})
            assertFalse(PreviewColourMath.isUseful(flat))
            assertNull(PreviewColourMath.estimate(pattern(),flat))
        }
    }
    @Test fun letterboxDoesNotBecomeBrightnessCorrection() {
        val active=pattern(128,54)
        val boxed=PreviewColourPixels(128,72,IntArray(128*72){i->
            val y=i/128
            if(y in 9 until 63) active.argb[(y-9)*128+i%128] else 0xff000000.toInt()
        })
        assertTrue(requireNotNull(PreviewColourMath.estimate(pattern(),boxed)).isIdentity)
    }
    @Test fun extremeInputCannotProduceAnExtremeFilter() {
        val target=pattern{r,g,b->doubleArrayOf(r*1.25+.02,g*1.25+.02,b*1.25+.02)}
        val c=PreviewColourMath.estimate(pattern(),target)
        if(c!=null){
            assertTrue(c.contrast in .91f..1.09f)
            assertTrue(c.saturation in .8874f..1.1876f)
            assertTrue(maxOf(abs(c.redOffset),abs(c.greenOffset),abs(c.blueOffset))<=.0563f)
        }
    }
    @Test fun captureCannotCrossSeekOrSourceChange() {
        assertTrue(PreviewColourCapturePolicy.matches(10_000,9_960,10_030,true))
        assertFalse(PreviewColourCapturePolicy.matches(10_000,9_960,30_030,true))
        assertFalse(PreviewColourCapturePolicy.matches(10_000,10_010,9_960,true))
        assertFalse(PreviewColourCapturePolicy.matches(10_000,9_960,10_030,false))
        assertFalse(PreviewColourCapturePolicy.matches(10_000,8_960,9_030,true))
    }
}
