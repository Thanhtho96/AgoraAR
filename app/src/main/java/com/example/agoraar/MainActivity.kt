package com.example.agoraar

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.agoraar.util.AgoraVideoRender
import com.example.agoraar.util.AgoraVideoSource
import com.example.agoraar.util.HalfLinearLayoutManager
import io.agora.rtc.Constants
import io.agora.rtc.IRtcEngineEventHandler
import io.agora.rtc.RtcEngine
import io.agora.rtc.mediaio.MediaIO
import io.agora.rtc.video.VideoCanvas
import io.agora.rtc.video.VideoEncoderConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : PermissionsActivity() {

    private var mSource: AgoraVideoSource? = null
    private var mRender: AgoraVideoRender? = null
    private var mRtcEngine: RtcEngine? = null
    private var callInProgress = false
    private var remoteViewContainer: RecyclerView? = null
    private var videoAdapter: VideoAdapter? = null
    private val viewDataList: MutableList<ViewData> = ArrayList()
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var mOnGetPreviewListener: OnGetImageListener

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        remoteViewContainer = findViewById(R.id.rcv)
        checkMultiplePermissions(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH
            ),
            "The app needs camera, external storage and record audio permissions",
            100,
            object : MultiplePermissionsCallback {
                override fun onAllPermissionsGranted() {
                    setup()
                }

                override fun onPermissionsDenied(deniedPermissions: List<String>) {
                    Log.d("MainActivity", "Permissions Denied!")
                }
            })
    }

    fun setup() {
        initializeEngine()
        setupVideoConfig()
        // add SurfaceView of Local Video to Adapter
        // and submitList to notify RecyclerView to re-draw
        mOnGetPreviewListener = OnGetImageListener(this)
        mOnGetPreviewListener.holder.addCallback(mOnGetPreviewListener)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        lifecycleScope.launch(Dispatchers.IO) {
            mOnGetPreviewListener.initialize()
        }

        remoteViewContainer?.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        viewDataList.add(ViewData(mOnGetPreviewListener))

        videoAdapter = VideoAdapter()
        val linearLayoutManager = HalfLinearLayoutManager(
            this,
            RecyclerView.VERTICAL,
            false
        )
        remoteViewContainer?.layoutManager = linearLayoutManager
        remoteViewContainer?.adapter = videoAdapter
        videoAdapter?.submitList(ArrayList(viewDataList))
        val btn = findViewById<Button>(R.id.startCall)
        mRtcEngine?.setExternalVideoSource(true, true, true)
        btn.setOnClickListener {
            if (callInProgress) {
                callInProgress = false
                mRtcEngine?.leaveChannel()
                onRemoteUserLeft()
                btn.text = "Start the call"
            } else {
                callInProgress = true
                joinChannel()
                btn.text = "End the call"
            }
            videoAdapter?.notifyItemChanged(0)
        }
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // CameraProvider
            cameraProvider = cameraProviderFuture.get()
            // Select lensFacing depending on the available cameras
            lensFacing = when {
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }
            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Declare and bind preview, capture and analysis use cases */
    @SuppressLint("UnsafeExperimentalUsageError")
    private fun bindCameraUseCases() {
        val screenAspectRatio = aspectRatio(mOnGetPreviewListener.width, mOnGetPreviewListener.height)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")
        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")
        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, { image ->
                    image.image?.let { img ->
                        val bitmap = mOnGetPreviewListener.onImageAvailable(
                            img,
                            image.imageInfo.rotationDegrees,
                            mirrored
                        )
                        if (callInProgress) sendARView(bitmap)
                    }
                    image.close()
                })
            }
        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun sendARView(bitmap: Bitmap?) {
        if (bitmap == null) return
        val width = bitmap.width
        val height = bitmap.height
        val size = bitmap.rowBytes * bitmap.height
        val byteBuffer = ByteBuffer.allocate(size)
        bitmap.copyPixelsToBuffer(byteBuffer)
        val data: ByteArray = byteBuffer.array()
        mSource?.consumer?.consumeByteArrayFrame(
            data,
            MediaIO.PixelFormat.RGBA.intValue(),
            width,
            height,
            0,
            System.currentTimeMillis()
        )
    }

    /**
     *  [androidx.camera.core.impl.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera() =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera() =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false

    override fun onDestroy() {
        viewDataList.clear()
        videoAdapter?.submitList(ArrayList(viewDataList))
        remoteViewContainer?.removeAllViews()
        super.onDestroy()
        mRtcEngine?.leaveChannel()
        mOnGetPreviewListener.deInitialize()
        RtcEngine.destroy()
    }

    private val mRtcEventHandler: IRtcEngineEventHandler = object : IRtcEngineEventHandler() {
        override fun onWarning(warn: Int) {
            Log.e(TAG, "warning: $warn")
        }

        override fun onError(err: Int) {
            Log.e(TAG, "error: $err")
        }

        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            runOnUiThread {
                Log.e(TAG, "onJoinChannelSuccess")
            }
        }

        override fun onFirstRemoteVideoDecoded(uid: Int, width: Int, height: Int, elapsed: Int) {
            runOnUiThread {
                Log.e(TAG, "onFirstRemoteVideoDecoded")
                setupRemoteVideo(uid)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread {
                Log.e(TAG, "onUserOffline")
                onRemoteUserLeft()
            }
        }
    }

    private fun initializeEngine() {
        mRtcEngine = try {
            RtcEngine.create(this, "91ba75957b7648ce8287e0d23baa41a8", mRtcEventHandler)
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
            throw RuntimeException(
                "NEED TO check rtc sdk init fatal error ${Log.getStackTraceString(e)}"
            )
        }
        mSource = AgoraVideoSource()
        mRender = AgoraVideoRender(0, true)
        mRtcEngine?.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
    }

    private fun setupVideoConfig() {
        mRtcEngine?.enableVideo()
        mRtcEngine?.setExternalVideoSource(true, true, true)
        // Please go to this page for detailed explanation
        // https://docs.agora.io/en/Video/API%20Reference/java/classio_1_1agora_1_1rtc_1_1_rtc_engine.html#af5f4de754e2c1f493096641c5c5c1d8f
        mRtcEngine?.setVideoEncoderConfiguration(
            VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_480x480,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
            )
        )
    }

    private fun joinChannel() {
        mRtcEngine?.setVideoSource(mSource)
        mRtcEngine?.setLocalVideoRenderer(mRender)
        mRtcEngine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        mRtcEngine?.joinChannel(null, "channel", "Extra Optional Data", 0)
    }

    private fun setupRemoteVideo(uid: Int) {
        val surfaceView = RtcEngine.CreateRendererView(this)
        mRtcEngine?.setupRemoteVideo(VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid))
        viewDataList.add(ViewData(surfaceView))
        videoAdapter?.submitList(ArrayList(viewDataList))
    }

    private fun onRemoteUserLeft() {
        if (viewDataList.size > 1) {
            viewDataList.removeAt(1)
            videoAdapter?.submitList(ArrayList(viewDataList))
        }
    }

    companion object {

        private const val TAG = "MainActivity"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}