package co.aospa.sense.eye

import android.content.Context
import android.util.Log
import co.aospa.sense.util.Constants
import co.aospa.sense.util.PreferenceHelper
import co.aospa.sense.util.Util
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device periocular & iris biometrics engine for camera-based eye unlock.
 * Operates directly on the luminance (Y) plane of NV21 camera frames to compute
 * texture descriptors, eye aspect ratios, and gaze alignment scores.
 */
object EyeRecognitionEngine {

    private const val TAG = "EyeRecognitionEngine"
    private const val MATCH_THRESHOLD = 0.75f
    private const val MIN_EYE_OPENNESS = 0.22f
    private const val MIN_GAZE_SCORE = 0.60f
    private const val LBP_GRID_X = 4
    private const val LBP_GRID_Y = 4
    private const val DESCRIPTOR_LENGTH = LBP_GRID_X * LBP_GRID_Y * 16 // 256 dimensions

    /**
     * Extracts periocular and iris features from the NV21 camera buffer.
     */
    fun extractEyeBiometrics(
        nv21Data: ByteArray,
        width: Int,
        height: Int,
        templateId: Int = 1
    ): EyeBiometrics? {
        if (width <= 0 || height <= 0 || nv21Data.size < width * height) {
            return null
        }

        // Define region of interest for eyes in upper half of portrait camera feed
        val roiTop = (height * 0.20f).toInt()
        val roiBottom = (height * 0.55f).toInt()
        val roiLeft = (width * 0.15f).toInt()
        val roiRight = (width * 0.85f).toInt()

        val roiWidth = roiRight - roiLeft
        val roiHeight = roiBottom - roiTop

        if (roiWidth <= 32 || roiHeight <= 16) {
            return null
        }

        // Segment left eye and right eye sub-regions
        val eyeBandWidth = roiWidth / 2
        val leftEyeX = roiLeft
        val rightEyeX = roiLeft + eyeBandWidth

        // Compute eye openness (luminance gradients indicating open eyelids vs closed)
        val leftOpenness = computeEyeOpenness(nv21Data, width, leftEyeX, roiTop, eyeBandWidth, roiHeight)
        val rightOpenness = computeEyeOpenness(nv21Data, width, rightEyeX, roiTop, eyeBandWidth, roiHeight)

        // Compute gaze alignment (pupil centering within ocular contours)
        val gazeScore = computeGazeAlignment(nv21Data, width, leftEyeX, rightEyeX, roiTop, eyeBandWidth, roiHeight)

        if (Util.IS_DEBUG_LOGGING) {
            Log.d(TAG, "extractEyeBiometrics: leftOpen=$leftOpenness, rightOpen=$rightOpenness, gaze=$gazeScore")
        }

        // Reject closed eyes or off-screen gaze
        if (leftOpenness < MIN_EYE_OPENNESS || rightOpenness < MIN_EYE_OPENNESS || gazeScore < MIN_GAZE_SCORE) {
            return null
        }

        // Extract periocular Local Binary Pattern (LBP) histogram descriptor
        val featureVector = extractPeriocularDescriptor(nv21Data, width, roiLeft, roiTop, roiWidth, roiHeight)

        return EyeBiometrics(
            templateId = templateId,
            featureVector = featureVector,
            leftEyeOpenness = leftOpenness,
            rightEyeOpenness = rightOpenness,
            gazeAlignmentScore = gazeScore
        )
    }

    /**
     * Estimates eye openness using vertical gradient contrast across the ocular slit.
     */
    private fun computeEyeOpenness(
        yPlane: ByteArray,
        stride: Int,
        startX: Int,
        startY: Int,
        w: Int,
        h: Int
    ): Float {
        var verticalContrast = 0.0f
        var count = 0
        val stepX = 2
        val stepY = 2

        for (y in (startY + h / 4) until (startY + 3 * h / 4) step stepY) {
            for (x in (startX + w / 4) until (startX + 3 * w / 4) step stepX) {
                val current = yPlane[y * stride + x].toInt() and 0xFF
                val top = yPlane[(y - 2) * stride + x].toInt() and 0xFF
                val bottom = yPlane[(y + 2) * stride + x].toInt() and 0xFF
                verticalContrast += abs(current - (top + bottom) / 2.0f)
                count++
            }
        }

        if (count == 0) return 0.0f
        val avgContrast = verticalContrast / count
        // Normalize between 0.0 and 1.0
        return (avgContrast / 40.0f).coerceIn(0.0f, 1.0f)
    }

    /**
     * Evaluates gaze alignment by locating minimum intensity centroids (pupils)
     * and checking if they are centered horizontally within the ocular cavities.
     */
    private fun computeGazeAlignment(
        yPlane: ByteArray,
        stride: Int,
        leftX: Int,
        rightX: Int,
        topY: Int,
        eyeW: Int,
        eyeH: Int
    ): Float {
        val leftPupilX = findPupilCentroidX(yPlane, stride, leftX, topY, eyeW, eyeH)
        val rightPupilX = findPupilCentroidX(yPlane, stride, rightX, topY, eyeW, eyeH)

        val leftRatio = (leftPupilX - leftX).toFloat() / eyeW
        val rightRatio = (rightPupilX - rightX).toFloat() / eyeW

        val leftCentered = 1.0f - abs(leftRatio - 0.50f) * 2.0f
        val rightCentered = 1.0f - abs(rightRatio - 0.50f) * 2.0f

        val score = (leftCentered + rightCentered) / 2.0f
        return score.coerceIn(0.0f, 1.0f)
    }

    private fun findPupilCentroidX(
        yPlane: ByteArray,
        stride: Int,
        startX: Int,
        startY: Int,
        w: Int,
        h: Int
    ): Int {
        var minIntensity = 255
        var minX = startX + w / 2

        for (y in (startY + h / 4) until (startY + 3 * h / 4) step 2) {
            for (x in (startX + w / 4) until (startX + 3 * w / 4) step 2) {
                val intensity = yPlane[y * stride + x].toInt() and 0xFF
                if (intensity < minIntensity) {
                    minIntensity = intensity
                    minX = x
                }
            }
        }
        return minX
    }

    /**
     * Extracts a uniform grid of Local Binary Patterns (LBP) across the periocular region.
     */
    private fun extractPeriocularDescriptor(
        yPlane: ByteArray,
        stride: Int,
        startX: Int,
        startY: Int,
        w: Int,
        h: Int
    ): FloatArray {
        val descriptor = FloatArray(DESCRIPTOR_LENGTH)
        val cellW = w / LBP_GRID_X
        val cellH = h / LBP_GRID_Y

        var descOffset = 0
        for (gy in 0 until LBP_GRID_Y) {
            for (gx in 0 until LBP_GRID_X) {
                val cellLeft = startX + gx * cellW
                val cellTop = startY + gy * cellH
                val histogram = FloatArray(16)

                for (y in (cellTop + 1) until (cellTop + cellH - 1)) {
                    for (x in (cellLeft + 1) until (cellLeft + cellW - 1)) {
                        val center = yPlane[y * stride + x].toInt() and 0xFF
                        var code = 0
                        // 8-neighbor comparison
                        if ((yPlane[(y - 1) * stride + (x - 1)].toInt() and 0xFF) >= center) code = code or (1 shl 0)
                        if ((yPlane[(y - 1) * stride + x].toInt() and 0xFF) >= center) code = code or (1 shl 1)
                        if ((yPlane[(y - 1) * stride + (x + 1)].toInt() and 0xFF) >= center) code = code or (1 shl 2)
                        if ((yPlane[y * stride + (x + 1)].toInt() and 0xFF) >= center) code = code or (1 shl 3)
                        if ((yPlane[(y + 1) * stride + (x + 1)].toInt() and 0xFF) >= center) code = code or (1 shl 4)
                        if ((yPlane[(y + 1) * stride + x].toInt() and 0xFF) >= center) code = code or (1 shl 5)
                        if ((yPlane[(y + 1) * stride + (x - 1)].toInt() and 0xFF) >= center) code = code or (1 shl 6)
                        if ((yPlane[y * stride + (x - 1)].toInt() and 0xFF) >= center) code = code or (1 shl 7)

                        histogram[code % 16] += 1.0f
                    }
                }

                // Normalize cell histogram
                var sum = 0.0f
                for (v in histogram) sum += v * v
                val norm = if (sum > 0.0f) sqrt(sum) else 1.0f
                for (i in 0 until 16) {
                    descriptor[descOffset + i] = histogram[i] / norm
                }
                descOffset += 16
            }
        }

        return descriptor
    }

    /**
     * Computes Cosine Similarity between candidate eye vector and enrolled template.
     */
    fun computeSimilarity(candidate: EyeBiometrics, template: EyeBiometrics): Float {
        val v1 = candidate.featureVector
        val v2 = template.featureVector
        val len = min(v1.size, v2.size)
        if (len == 0) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until len) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        if (normA <= 0.0f || normB <= 0.0f) return 0.0f
        val similarity = dotProduct / (sqrt(normA) * sqrt(normB))
        return similarity.coerceIn(0.0f, 1.0f)
    }

    /**
     * Compares a candidate eye frame against all enrolled templates.
     * Returns matching template ID if confidence >= MATCH_THRESHOLD, otherwise -1.
     */
    fun matchCandidate(candidate: EyeBiometrics, enrolledTemplates: List<EyeBiometrics>): Int {
        var bestScore = 0.0f
        var matchedId = -1

        for (template in enrolledTemplates) {
            val score = computeSimilarity(candidate, template)
            if (Util.IS_DEBUG_LOGGING) {
                Log.d(TAG, "Comparing candidate against template ${template.templateId}: score=$score")
            }
            if (score > bestScore && score >= MATCH_THRESHOLD) {
                bestScore = score
                matchedId = template.templateId
            }
        }
        return matchedId
    }

    // Storage and Management
    fun saveEyeTemplate(context: Context, biometrics: EyeBiometrics) {
        val helper = PreferenceHelper(context)
        val templates = loadEnrolledEyeTemplates(context).toMutableList()
        templates.removeAll { it.templateId == biometrics.templateId }
        templates.add(biometrics)

        val jsonArr = JSONArray()
        for (t in templates) {
            jsonArr.put(t.toJson())
        }
        val serialized = jsonArr.toString()
        helper.saveStringValue(Constants.SHARED_KEY_EYE_TEMPLATES, serialized)
        try {
            android.provider.Settings.Secure.putStringForUser(
                context.contentResolver,
                Constants.SHARED_KEY_EYE_TEMPLATES,
                serialized,
                Util.getUserId(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save eye templates to Settings.Secure", e)
        }
    }

    fun loadEnrolledEyeTemplates(context: Context): List<EyeBiometrics> {
        val helper = PreferenceHelper(context)
        var raw = helper.getStringValueByKey(Constants.SHARED_KEY_EYE_TEMPLATES)
        if (raw.isNullOrEmpty()) {
            try {
                raw = android.provider.Settings.Secure.getStringForUser(
                    context.contentResolver,
                    Constants.SHARED_KEY_EYE_TEMPLATES,
                    Util.getUserId(context)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load eye templates from Settings.Secure", e)
            }
        }
        if (raw.isNullOrEmpty()) return emptyList()
        val list = ArrayList<EyeBiometrics>()
        try {
            val jsonArr = JSONArray(raw)
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.optJSONObject(i)
                if (obj != null) {
                    list.add(EyeBiometrics.fromJson(obj))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse eye templates", e)
        }
        return list
    }

    fun deleteEyeTemplate(context: Context, templateId: Int) {
        val helper = PreferenceHelper(context)
        val templates = loadEnrolledEyeTemplates(context).filter { it.templateId != templateId }
        val jsonArr = JSONArray()
        for (t in templates) {
            jsonArr.put(t.toJson())
        }
        val serialized = jsonArr.toString()
        helper.saveStringValue(Constants.SHARED_KEY_EYE_TEMPLATES, serialized)
        try {
            android.provider.Settings.Secure.putStringForUser(
                context.contentResolver,
                Constants.SHARED_KEY_EYE_TEMPLATES,
                serialized,
                Util.getUserId(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete eye template from Settings.Secure", e)
        }
    }

    fun clearAllEyeTemplates(context: Context) {
        val helper = PreferenceHelper(context)
        helper.removeSharePreferences(Constants.SHARED_KEY_EYE_TEMPLATES)
        try {
            android.provider.Settings.Secure.putStringForUser(
                context.contentResolver,
                Constants.SHARED_KEY_EYE_TEMPLATES,
                "",
                Util.getUserId(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear eye templates from Settings.Secure", e)
        }
    }
}
