package app.steady.data

import app.steady.detect.DetectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The seed the build copies into assets (detectors/detectors.v1.json) must decode. */
class DetectorSeedTest {
    @Test fun seedConfigDecodes() {
        val file = listOf("../../shared/detectors/detectors.v1.json", "../shared/detectors/detectors.v1.json")
            .map(::File).first { it.exists() }
        val config = DetectorCodec.decode(file.readText())
        assertEquals(1, config.version)
        val surfaces = config.detectors.map { it.surfaceId }.toSet()
        assertTrue(surfaces.containsAll(setOf("instagram.reels", "youtube.shorts", "snapchat.spotlight", "facebook.reels")))
    }
}
