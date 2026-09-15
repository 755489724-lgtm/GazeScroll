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
 * 为什么判定这一帧不可信（v4.9）。
 *
 * 手放到脸上会以**多种方式**破坏数据，只堵其中一种是不够的（v4.8 只堵了 [MOUTH_MISSING]，
 * 所以"整只手盖住脸"这种情况照样漏过去）：
 */
enum class OcclusionReason(val label: String) {
    /** 人脸彻底消失（整只手 / 拳头盖住）。由服务侧的人脸丢失状态机判定。 */
    FACE_LOST("face lost"),

    /** 有人脸，但读不出嘴的关键点（手挡下半脸）。 */
    MOUTH_MISSING("mouth missing"),

    /** 人脸框尺寸在一帧之内剧烈变化，说明有东西贴上来或糊住了镜头。 */
    FACE_SIZE_JUMP("face size jump"),
}

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
    /**
     * ML Kit `headEulerAngleY`: the yaw used for the left/right head-turn gestures.
     *
     * Note the mirroring: this is a **front** camera, so turning your head to your
     * own right makes the detected face turn towards its left in image space, i.e.
     * the sign comes out opposite to the physical direction. That is handled once,
     * in [HeadPoseDetector], and can be flipped from the settings if a device
     * disagrees.
     */
    val headEulerAngleY: Float?,
    /**
     * 张嘴比例（v4.5）：`MOUTH_BOTTOM` 到 `NOSE_BASE` 的距离 / 同帧人脸框高度。
     *
     * 用比例而不是像素，所以与离手机远近、机型分辨率都无关。null 表示这一帧缺少
     * 需要的关键点（没脸、或关键点不可用），此时 [MouthOpenDetector] 不做任何判定。
     */
    val mouthOpenRatio: Float?,
    /**
     * 嘴到鼻底的**像素**距离（v5.2）。
     *
     * 与 [mouthOpenRatio] 的比值不同，这是绝对值：脸凑近摄像头时它会成倍变大（比值不会），
     * 所以它对「有东西挡住下半脸」更敏感。实测比值本身在 0.195~0.260 之间随机摆动
     * （噪声比张嘴信号还大），单靠比值判不出遮挡，需要这个绝对量做交叉验证。
     */
    val mouthNoseGapPx: Float?,
    /**
     * 人脸框高度占画面高度的比例（v4.9）。
     *
     * 用来估算**离手机多远**：脸占比越大说明凑得越近。近距离时同样的微小晃动在画面里
     * 折算出的角度更大，静止锁定必须据此抬高门限，否则贴着手机看就会一直误触。
     * null 表示没检测到脸。
     */
    val faceRatio: Float?,
    /** 这一帧为什么不可信；null 表示数据正常。 */
    val occlusionReason: OcclusionReason?,
    val faceDetected: Boolean,
    val standby: Boolean,
) {
    /** 距离档位，仅用于界面展示与日志。 */
    val distanceLabel: String
        get() = when (val ratio = faceRatio) {
            null -> "未知"
            else -> when {
                ratio >= NEAR_FACE_RATIO -> "近"
                ratio >= MID_FACE_RATIO -> "中"
                else -> "远"
            }
        }

    companion object {
        /** 脸高占画面 ≥ 这么大算「凑得很近」（约 30cm，小米 13 实测）。 */
        const val NEAR_FACE_RATIO = 0.55f

        /** 脸高占画面 ≥ 这么大算「中等距离」（约 50cm）。 */
        const val MID_FACE_RATIO = 0.38f
    }
}

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

        /** 一帧之间脸框高度占比变化超过这个值，判定为「有东西贴上来了」。 */
        private const val FACE_RATIO_JUMP = 0.35f
    }

    /** 上一帧的脸框高度占比，用来检测尺寸骤变。 */
    private var lastFaceRatio: Float? = null

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

        // 人脸框高度占画面高度的比例 —— 就是「离手机多远」的度量。
        val faceRatio = if (face != null && uprightHeight > 0f) {
            (face.boundingBox.height() / uprightHeight).coerceIn(0f, 1f)
        } else {
            null
        }
        val mouth = mouthRatio(face)

        // 尺寸骤变：一帧之间脸框高度变化超过 35%，说明画面里刚有东西贴上来 / 移走。
        // 正常转头、点头都不会让脸框尺寸一帧变三成，所以这是个很干净的遮挡信号。
        val sizeJump = faceRatio != null && lastFaceRatio != null &&
            kotlin.math.abs(faceRatio - lastFaceRatio!!) > FACE_RATIO_JUMP
        if (faceRatio != null) lastFaceRatio = faceRatio

        val reason = when {
            // 「没脸」交给服务侧的人脸丢失状态机判定（它才知道丢了多久），这里只报尺寸跳变。
            face == null -> null
            mouth == null -> OcclusionReason.MOUTH_MISSING
            sizeJump -> OcclusionReason.FACE_SIZE_JUMP
            else -> null
        }

        onFrame(
            AnalyzedFrame(
                gaze = buildGazeSample(face, uprightHeight),
                leftEyeOpenProbability = face?.leftEyeOpenProbability,
                rightEyeOpenProbability = face?.rightEyeOpenProbability,
                headEulerAngleX = face?.headEulerAngleX,
                headEulerAngleY = face?.headEulerAngleY,
                mouthOpenRatio = mouth,
                mouthNoseGapPx = mouthNoseGap(face),
                faceRatio = faceRatio,
                occlusionReason = reason,
                faceDetected = face != null,
                standby = standby,
            )
        )
    }

    /** Largest face wins — that is the person holding the phone. */
    private fun pickLargestFace(faces: List<Face>): Face? =
        faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height().toLong() }

    /**
     * 嘴到鼻底的像素距离（v5.2），独立于归一化比值。
     *
     * 比值把距离和脸的大小约掉了，判遮挡时反而丢掉了「绝对尺度」这个信息；这里保留原始
     * 像素距离，供服务侧做交叉验证（脸凑近时它会成倍增长，遮挡时它会突然塌缩）。
     */
    private fun mouthNoseGap(face: Face?): Float? {
        if (face == null) return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.y ?: return null
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.y ?: return null
        return kotlin.math.abs(mouthBottom - noseBase)
    }

    /**
     * 张嘴比例：`MOUTH_BOTTOM` 到 `NOSE_BASE` 的垂直距离 / 人脸框高度。
     *
     * 为什么选这两个点：鼻底在整个头部动作里几乎不动，而下唇是张嘴时位移最大的地方，
     * 两者之差对「张嘴」最敏感、对「转头/点头」最不敏感。ML Kit 的关键点模式已经开启
     * （`LANDMARK_MODE_ALL`），所以这两个点是现成的，不需要额外引入人脸网格模型。
     *
     * 除以人脸框高度是关键的归一化：脸在画面里越远越小，分子和分母同比例缩小，
     * 比例保持不变，因此阈值可以跨距离、跨机型通用（[MouthOpenDetector] 在此基础上
     * 还会学习用户本人的闭嘴基准，进一步消除个体差异）。
     *
     * 取 `abs` 是因为俯仰角较大时两点的先后顺序可能翻转；缺关键点或脸框无效时返回
     * null，调用方据此跳过这一帧而不是拿 0 当作「闭嘴」。
     */
    private fun mouthRatio(face: Face?): Float? {
        if (face == null) return null
        val faceHeight = face.boundingBox.height().toFloat()
        if (faceHeight <= 1f) return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.y ?: return null
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.y ?: return null
        return (kotlin.math.abs(mouthBottom - noseBase) / faceHeight).coerceIn(0f, 1f)
    }
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
