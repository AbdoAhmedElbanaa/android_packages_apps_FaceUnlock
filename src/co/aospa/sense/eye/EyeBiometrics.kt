package co.aospa.sense.eye

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encapsulates ocular and periocular biometric features extracted from the camera frame.
 */
data class EyeBiometrics(
    val templateId: Int,
    val featureVector: FloatArray,
    val leftEyeOpenness: Float,
    val rightEyeOpenness: Float,
    val gazeAlignmentScore: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("templateId", templateId)
        val arr = JSONArray()
        for (f in featureVector) {
            arr.put(f.toDouble())
        }
        json.put("features", arr)
        json.put("leftOpenness", leftEyeOpenness.toDouble())
        json.put("rightOpenness", rightEyeOpenness.toDouble())
        json.put("gazeScore", gazeAlignmentScore.toDouble())
        json.put("timestamp", timestamp)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): EyeBiometrics {
            val templateId = json.optInt("templateId", 1)
            val arr = json.optJSONArray("features") ?: JSONArray()
            val features = FloatArray(arr.length())
            for (i in 0 until arr.length()) {
                features[i] = arr.optDouble(i, 0.0).toFloat()
            }
            val leftOpen = json.optDouble("leftOpenness", 0.8).toFloat()
            val rightOpen = json.optDouble("rightOpenness", 0.8).toFloat()
            val gaze = json.optDouble("gazeScore", 0.9).toFloat()
            val time = json.optLong("timestamp", 0L)
            return EyeBiometrics(templateId, features, leftOpen, rightOpen, gaze, time)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EyeBiometrics
        return templateId == other.templateId && featureVector.contentEquals(other.featureVector)
    }

    override fun hashCode(): Int {
        var result = templateId
        result = 31 * result + featureVector.contentHashCode()
        return result
    }
}
