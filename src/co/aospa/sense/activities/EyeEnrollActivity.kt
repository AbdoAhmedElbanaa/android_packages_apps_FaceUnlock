package co.aospa.sense.activities

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import co.aospa.sense.R
import co.aospa.sense.controller.FaceEnrollController
import co.aospa.sense.controller.FaceEnrollController.CameraCallback
import co.aospa.sense.eye.EyeBiometrics
import co.aospa.sense.eye.EyeRecognitionEngine
import co.aospa.sense.view.CircleSurfaceView
import com.google.android.setupdesign.GlifLayout

open class EyeEnrollActivity : FaceBaseActivity() {

    private var mEnrollController: FaceEnrollController? = null
    private var mSurfaceView: CircleSurfaceView? = null
    private var mEnrollMessage: TextView? = null
    private var mProgress = 0.0f
    private var mHasCameraPermission = false
    private var mIsEnrolled = false

    private val mCameraCallback: CameraCallback = object : CameraCallback {
        override fun handleSaveFeature(data: ByteArray?, width: Int, height: Int, angle: Int): Int {
            if (mIsEnrolled || data == null) return -1

            val existing = EyeRecognitionEngine.loadEnrolledEyeTemplates(this@EyeEnrollActivity)
            val newId = (existing.maxOfOrNull { it.templateId } ?: 0) + 1

            val eyeBio = EyeRecognitionEngine.extractEyeBiometrics(data, width, height, newId)
            if (eyeBio != null) {
                runOnUiThread {
                    mProgress += 15.0f
                    if (mProgress > 100.0f) mProgress = 100.0f
                    mSurfaceView?.setProgress(mProgress)
                    mEnrollMessage?.setText(R.string.eye_enroll_description)

                    if (mProgress >= 100.0f && !mIsEnrolled) {
                        mIsEnrolled = true
                        completeEnrollment(eyeBio)
                    }
                }
            } else {
                runOnUiThread {
                    mEnrollMessage?.setText(R.string.eye_look_at_camera)
                }
            }
            return 0
        }

        override fun setDetectArea(size: Camera.Size?) {}
        override fun handleSaveFeatureResult(result: Int) {}
        override fun onFaceDetected() {}
        override fun onTimeout() {
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
        override fun onCameraError() {
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.face_enroll)
        setHeaderText(R.string.eye_enroll_title)
        getLayout().setDescriptionText(R.string.eye_enroll_description)
        mSurfaceView = findViewById(R.id.camera_surface)
        mSurfaceView?.setProgress(0.0f)
        mEnrollMessage = findViewById(R.id.face_vendor_message)
        mEnrollMessage?.setText(R.string.eye_look_at_camera)

        mHasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!mHasCameraPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (mHasCameraPermission) {
            startCameraCapture()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCameraCapture()
    }

    private fun startCameraCapture() {
        if (mEnrollController == null) {
            mEnrollController = FaceEnrollController.instance
            mEnrollController?.setSurfaceHolder(mSurfaceView?.holder)
        }
        mEnrollController?.start(mCameraCallback, 25000)
    }

    private fun stopCameraCapture() {
        mEnrollController?.stop(mCameraCallback)
    }

    private fun completeEnrollment(biometrics: EyeBiometrics) {
        stopCameraCapture()
        EyeRecognitionEngine.saveEyeTemplate(this, biometrics)
        val vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(120)
        }
        Toast.makeText(this, R.string.eye_unlock_title, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mHasCameraPermission = true
            startCameraCapture()
        } else {
            finish()
        }
    }

    override fun getLayout(): GlifLayout {
        return findViewById(R.id.face_enroll)
            ?: throw IllegalStateException("Missing required GlifLayout view with id 'face_enroll'")
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 101
    }
}
