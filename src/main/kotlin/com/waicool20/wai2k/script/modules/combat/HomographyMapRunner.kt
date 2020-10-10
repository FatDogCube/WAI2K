/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.script.modules.combat

import ai.djl.inference.Predictor
import ai.djl.metric.Metrics
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.waicool20.cvauto.android.AndroidRegion
import com.waicool20.cvauto.util.transformRect
import com.waicool20.wai2k.script.ScriptComponent
import com.waicool20.wai2k.util.ai.MatchingModel
import com.waicool20.wai2k.util.ai.MatchingTranslator
import com.waicool20.waicoolutils.logging.loggerFor
import georegression.struct.homography.Homography2D_F64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

abstract class HomographyMapRunner(scriptComponent: ScriptComponent) : MapRunner(scriptComponent) {
    private val logger = loggerFor<HomographyMapRunner>()

    companion object {
        /**
         * Minimum scroll in pixels, because sometimes smaller scrolls dont register properly
         */
        private const val minScroll = 75
    }

    private val metrics = Metrics()

    private val predictor: Predictor<Pair<Image, Image>, Homography2D_F64>

    /**
     * Map homography cache
     */
    private var mapH: Homography2D_F64? = null

    final override val nodes: List<MapNode>

    val fullMap: Image

    init {
        val p = async(Dispatchers.IO) {
            val model = MatchingModel(
                config.assetsDirectory.resolve("models/SuperPoint.pt"),
                config.assetsDirectory.resolve("models/SuperGlue.pt")
            )
            val translator = MatchingTranslator(480, 360)
            // Preload the model to device memory
            translator.prepare(model.ndManager, model)
            model.newPredictor(translator).apply { setMetrics(metrics) }
        }
        val n = async(Dispatchers.IO) {
            val path = config.assetsDirectory.resolve("$PREFIX/map.json")
            if (Files.exists(path)) {
                jacksonObjectMapper().readValue<List<MapNode>>(path.toFile())
            } else {
                emptyList()
            }
        }
        val fm = async(Dispatchers.IO) {
            ImageFactory.getInstance().fromFile(config.assetsDirectory.resolve("$PREFIX/map.png"))
        }

        predictor = runBlocking { p.await() }
        nodes = runBlocking { n.await() }
        fullMap = runBlocking { fm.await() }
    }

    /**
     * Difference threshold between reference node coordinate values in map.json
     * and estimated rect values
     */
    protected open val maxMapDiff = 80.0

    /**
     * Difference threshold between estimated rect width and height,
     * it should be roughly square (1:1)
     */
    protected open val maxSideDiff = 5.0

    override suspend fun cleanup() {
        mapH = null
    }

    override suspend fun MapNode.findRegion(): AndroidRegion {
        val window = mapRunnerRegions.window

        suspend fun retry(): AndroidRegion {
            logger.info("Zoom out")
            region.pinch(
                Random.nextInt(500, 700),
                Random.nextInt(300, 400),
                0.0,
                500
            )
            delay((900 * gameState.delayCoefficient).roundToLong()) //Wait to settle
            mapH = null
            return findRegion()
        }

        val h = mapH ?: try {
            logger.info("Finding map transformation")
            val prediction = predictor.predict(fullMap to ImageFactory.getInstance().fromImage(window.capture()))
            logger.debug("Homography prediction metrics:")
            logger.debug("Preprocess: ${metrics.latestMetric("Preprocess").value.toLong() / 1000000} ms")
            logger.debug("Inference: ${metrics.latestMetric("Inference").value.toLong() / 1000000} ms")
            logger.debug("Postprocess: ${metrics.latestMetric("Postprocess").value.toLong() / 1000000} ms")
            logger.debug("Total: ${metrics.latestMetric("Total").value.toLong() / 1000000} ms")
            mapH = prediction
            prediction
        } catch (e: MatchingTranslator.ModelMatchingFailedException) {
            return retry()
        }

        // Rect that is relative to the window
        val rect = h.transformRect(rect)
        logger.debug("$this estimated to be at Rect(x=${rect.x}, y=${rect.y}, width=${rect.width}, height=${rect.height})")
        if (rect.width <= 0 || rect.height <= 0) {
            logger.debug("Estimate failed basic dimension test, will retry")
            return retry()
        }
        val roi = window.copyAs<AndroidRegion>(
            window.x + rect.x,
            window.y + rect.y,
            rect.width,
            rect.height
        )
        val mapDiff = (rect.width.toDouble() - width).pow(2) + (rect.height.toDouble() - height).pow(2)
        if (mapDiff > maxMapDiff.pow(2)) {
            logger.info("Estimate failed map difference test, will retry | diff=$mapDiff, max=$maxMapDiff")
            return retry()
        }
        val sideDiff = (rect.width.toDouble() - rect.height).pow(2)
        if (sideDiff > maxSideDiff.pow(2)) {
            logger.debug("Estimate failed side difference test, will retry | diff=$sideDiff, max=$maxSideDiff")
            return retry()
        }
        if (!window.contains(roi)) {
            logger.info("Node $this not in map window")
            val center = region.subRegion(
                (region.width - 5) / 2,
                (region.height - 5) / 2,
                5, 5
            )
            // Add some randomness
            center.translate(Random.nextInt(-50, 50), Random.nextInt(-50, 50))
            when {
                roi.y < window.y -> {
                    val dist = max(window.y - roi.y, minScroll)
                    val from = center.copyAs<AndroidRegion>().apply { translate(0, -dist) }
                    val to = center.copyAs<AndroidRegion>().apply { translate(0, dist) }
                    logger.info("Scroll up $dist px")
                    from.swipeTo(to)
                }
                roi.y > window.y + window.height -> {
                    val dist = max(roi.y - (window.y + window.height), minScroll)
                    val from = center.copyAs<AndroidRegion>().apply { translate(0, dist) }
                    val to = center.copyAs<AndroidRegion>().apply { translate(0, -dist) }
                    logger.info("Scroll down $dist px")
                    from.swipeTo(to)
                }
            }
            when {
                roi.x < window.x -> {
                    val dist = max(window.x - roi.x, minScroll)
                    val from = center.copyAs<AndroidRegion>().apply { translate(-dist, 0) }
                    val to = center.copyAs<AndroidRegion>().apply { translate(dist, 0) }
                    logger.info("Scroll left $dist px")
                    from.swipeTo(to)
                }
                roi.x > window.x + window.width -> {
                    val dist = max(roi.x - (window.x + window.width), minScroll)
                    val from = center.copyAs<AndroidRegion>().apply { translate(dist, 0) }
                    val to = center.copyAs<AndroidRegion>().apply { translate(-dist, 0) }
                    logger.info("Scroll right $dist px")
                    from.swipeTo(to)
                }
            }
            mapH = null
            delay(200)
            return findRegion()
        }
        return roi
    }
}