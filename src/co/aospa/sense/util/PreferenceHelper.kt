package co.aospa.sense.util

import android.annotation.SuppressLint
import android.content.Context
import android.preference.PreferenceManager
import android.text.TextUtils

class PreferenceHelper(context: Context) {

    private val mContext: Context

    @SuppressLint("ApplySharedPref")
    fun saveIntValue(key: String?, value: Int) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putInt(key, value)
        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    fun saveBooleanValue(key: String?, value: Boolean) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putBoolean(key, value)
        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    fun removeSharePreferences(value: String?) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.remove(value)
        edit.commit()
    }

    fun getIntValueByKey(key: String?, value: Int): Int {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getInt(key, value)
    }

    fun getIntValueByKey(key: String?): Int {
        return getIntValueByKey(key, -1)
    }

    @SuppressLint("ApplySharedPref")
    fun saveStringValue(key: String?, value: String?) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putString(key, value)
        edit.commit()
    }

    fun getStringValueByKey(key: String?): String? {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getString(key, null)
    }

    @SuppressLint("ApplySharedPref")
    fun saveByteArrayValue(key: String?, value: ByteArray?) {
        val edit = PreferenceManager.getDefaultSharedPreferences(mContext).edit()
        edit.putString(key, String(value!!))
        edit.commit()
    }

    fun getByteArrayValueByKey(key: String?): ByteArray? {
        val string = PreferenceManager.getDefaultSharedPreferences(mContext).getString(key, null)
        return if (TextUtils.isEmpty(string)) {
            null
        } else string!!.toByteArray()
    }

    fun getBooleanValueByKey(key: String?): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(key, false)
    }

    fun getEnrolledFaceIds(): List<Int> {
        val raw = getStringValueByKey(Constants.SHARED_KEY_FACE_IDS)
        if (!TextUtils.isEmpty(raw)) {
            val list = raw!!.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (list.isNotEmpty()) return list
        }
        val singleId = getIntValueByKey(Constants.SHARED_KEY_FACE_ID)
        return if (singleId > 0) listOf(singleId) else emptyList()
    }

    @SuppressLint("ApplySharedPref")
    fun addEnrolledFaceId(faceId: Int) {
        val current = getEnrolledFaceIds().toMutableList()
        if (!current.contains(faceId)) {
            current.add(faceId)
        }
        saveStringValue(Constants.SHARED_KEY_FACE_IDS, current.joinToString(","))
        saveIntValue(Constants.SHARED_KEY_FACE_ID, faceId)
    }

    @SuppressLint("ApplySharedPref")
    fun removeEnrolledFaceId(faceId: Int) {
        val current = getEnrolledFaceIds().toMutableList()
        current.remove(faceId)
        if (current.isNotEmpty()) {
            saveStringValue(Constants.SHARED_KEY_FACE_IDS, current.joinToString(","))
            saveIntValue(Constants.SHARED_KEY_FACE_ID, current.last())
        } else {
            removeSharePreferences(Constants.SHARED_KEY_FACE_ID)
            removeSharePreferences(Constants.SHARED_KEY_FACE_IDS)
        }
    }

    @SuppressLint("ApplySharedPref")
    fun clearEnrolledFaceIds() {
        removeSharePreferences(Constants.SHARED_KEY_FACE_IDS)
        removeSharePreferences(Constants.SHARED_KEY_FACE_ID)
    }

    fun saveTokenForFace(faceId: Int, token: ByteArray?) {
        saveByteArrayValue("${Constants.SHARED_KEY_ENROLL_TOKEN}_$faceId", token)
        saveByteArrayValue(Constants.SHARED_KEY_ENROLL_TOKEN, token)
    }

    fun getTokenForFace(faceId: Int): ByteArray? {
        val token = getByteArrayValueByKey("${Constants.SHARED_KEY_ENROLL_TOKEN}_$faceId")
        return token ?: getByteArrayValueByKey(Constants.SHARED_KEY_ENROLL_TOKEN)
    }

    fun removeTokenForFace(faceId: Int) {
        removeSharePreferences("${Constants.SHARED_KEY_ENROLL_TOKEN}_$faceId")
    }

    fun getBiometricMode(): Int {
        return getIntValueByKey(Constants.SHARED_KEY_BIOMETRIC_MODE, Constants.MODE_FULL_FACE)
    }

    fun setBiometricMode(mode: Int) {
        saveIntValue(Constants.SHARED_KEY_BIOMETRIC_MODE, mode)
    }

    init {
        mContext = context.applicationContext
    }
}