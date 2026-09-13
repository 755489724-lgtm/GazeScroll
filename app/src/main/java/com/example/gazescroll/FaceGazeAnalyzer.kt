package com.example.gazescroll

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Everything one analysed frame produced.
 *
 * @param leftEyeOpenProbability  ML Kit eye-open probability, null when unavailable
 * @param rightEyeOpenProbability ML Kit eye-open probability, null when unavailable
 * @param standby true while the analyzer is throttled down to 1 fps
 */
data class AnalyzedFrame(
    val gaze: GazeSample,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    /** ML Kit `headEulerAngleX`: positive means the face is turned upward. */
    val headEulerAngleX: Float?,
    val faceDetected: Boolean,
    val standby: Boolean,
)

/**
 * CameraX analyzer: front camera frame -> ML Kit face -> [AnalyzedFrame].
 *
 * Two jobs:
 *  1. hand eye-open probabilities to the blink detector (the real trigger);
 *  2. hand eye landmark Y to the legacy gaze state machine (debug only).
 *
 * Coordinate handling — this is the part that is easy to get wrong:
 *
 *  - ML Kit returns landmarks in the coordinate frame of the ROTATED buffer,
 *    i.e. the upright frame. In portrait the CameraX buffer is landscape
 *    (e.g. 480x360) while `rotationDegrees` is 90/270, so the upright frame is
 *    360 wide by 480 tall. We therefore normalise Y by `imageProxy.width` in
 *    that case, and by `imageProxy.height` when the frame is already upright.
 *  - The front camera is mirrored horizontally, but mirroring only affects X.
 *    Since the scheme is driven by Y, no flip is needed here.
 *
 * Power strategy: CameraX is bound once and never restarted for throttling.
 * Frames are dropped here by timestamp instead — ~15 fps while a face is
 * present, 1 fps after [STANDBY_AFTER_NO_FACE_MS] without one.
 */
class FaceGazeAnalyzer(
    private val onFrame: (AnalyzedFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        /** Active-mode analysis cap: ~15 fps. */
        const val ACTIVE_MIN_INTERVAL_MS = 66L

        /** Standby-mode analysis rate: 1 fps. */
        const val STANDBY_INTERVAL_MS = 1000L

        /** No face for this long moves the analyzer into standby. */
        const val STANDBY_AFTER_NO_FACE_MS = 5000L
    }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // CLASSIFICATION_MODE_ALL is what makes leftEyeOpenProbability and
            // rightEyeOpenProbability available. Without it both are null.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
    )

    /** Screen is off: drop every frame without touching ML Kit. */
    @Volatile
    var paused: Boolean = false

    /** Per-eye EMA state for the legacy gaze axis, so a dropped eye cannot snap it. */
    private var smoothLeft: Float? = null
    private var smoothRight: Float? = null

    private var lastFaceSeenMs = 0L
    private var lastAnalyzedMs = 0L

    @Volatile
    var standby: Boolean = false
        private set

    fun resetSmoothing() {
        smoothLeft = null
        smoothRight = null
    }

    fun close() {
        runCatching { detector.close() }
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || paused) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()

        // Frame gating — the throttle lives here, not in CameraX.
        val minInterval = if (standby) STANDBY_INTERVAL_MS else ACTIVE_MIN_INTERVAL_MS
        if (now - lastAnalyzedMs < minInterval) {
            imageProxy.close()
            return
        }
        lastAnalyzedMs = now

        // First frame ever: treat it as "face seen just now" so we do not start
        // out in standby before there has been a chance to look.
        if (lastFaceSeenMs == 0L) lastFaceSeenMs = now

        val rotation = imageProxy.imageInfo.rotationDegrees
        // Upright frame height, per the note in the class doc.
        val uprightHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        val image = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(image)
            .addOnSuccessListener { faces -> deliver(faces, uprightHeight.toFloat(), now) }
            .addOnFailureListener { deliver(emptyList(), uprightHeight.toFloat(), now) }
            .addOnCompleteListener {
                // Must close on every path or the analysis pipeline stalls.
                imageProxy.close()
            }
    }

    private fun deliver(faces: List<Face>, uprightHeight: Float, nowMs: Long) {
        val face = pickLargestFace(faces)

        if (face != null) {
            lastFaceSeenMs = nowMs
            standby = false
        } else if (nowMs - lastFaceSeenMs >= STANDBY_AFTER_NO_FACE_MS) {
            standby = true
        }

        onFrame(
            AnalyzedFrame(
                gaze = buildGazeSample(face, uprightHeight),
                leftEyeOpenProbability = face?.leftEyeOpenProbability,
                rightEyeOpenProbability = face?.rightEyeOpenProbability,
                headEulerAngleX = face?.headEulerAngleX,
                faceDetected = face != null,
                standby = standby,
            )
        )
    }

    /** Largest face wins — that is the person holding the phone. */
    private fun pickLargestFace(faces: List<Face>): Face? =
        faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height().toLong() }

    /**
     * Legacy gaze axis. Only consumed when `GazeConfig.gazeModeEnabled` is on,
     * but it is cheap to maintain and keeps the debug path working.
     */
    private fun buildGazeSample(face: Face?, uprightHeight: Float): GazeSample {
        if (face == null || uprightHeight <= 0f) {
            smoothLeft = null
            smoothRight = null
            return GazeSample.EMPTY
        }

        val leftRaw = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.y
        val rightRaw = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.y
        if (leftRaw == null && rightRaw == null) {
            smoothLeft = null
            smoothRight = null
            return GazeSample.EMPTY
        }

        val cfg = GazeRuntime.config
        val alpha = cfg.smoothingAlpha.coerceIn(0.05f, 1f)
        val flip = cfg.invertY

        fun normalise(raw: Float?): Float? {
            if (raw == null) return null
            val n = (raw / uprightHeight).coerceIn(0f, 1f)
            return if (flip) 1f - n else n
        }

        val leftN = normalise(leftRaw)
        val rightN = normalise(rightRaw)

        smoothLeft = smooth(smoothLeft, leftN, alpha)
        smoothRight = smooth(smoothRight, rightN, alpha)

        val rawMean = when {
            leftN != null && rightN != null -> (leftN + rightN) / 2f
            leftN != null -> leftN
            else -> rightN
        }

        return GazeSample(
            leftEyeY = smoothLeft,
            rightEyeY = smoothRight,
            rawMeanY = rawMean,
        )
    }

    private fun smooth(previous: Float?, next: Float?, alpha: Float): Float? = when {
        next == null -> null
        previous == null -> next
        else -> previous + alpha * (next - previous)
    }
}
