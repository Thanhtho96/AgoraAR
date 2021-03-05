package com.example.agoraar.cameraX

import android.content.Context
import android.graphics.*
import android.media.Image
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.agoraar.R
import com.example.agoraar.util.computeExifOrientation
import com.example.agoraar.util.decodeExifOrientation
import com.tzutalin.dlib.FaceDet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Math.toDegrees
import kotlin.math.*

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
class OnGetImageListener(context: Context?) : SurfaceView(context),
    SurfaceHolder.Callback {

    private val TAG = javaClass::class.java.name
    private lateinit var cameraBitmap: Bitmap
    private lateinit var bitmap: Bitmap
    private var mFaceDet: FaceDet? = null
    private var mCascadeFile: File? = null
    private var yuvConverter = YuvToRgbConverter(getContext())
    private var surfaceHolder: SurfaceHolder? = null
    private val inputImageRect = Rect()
    private val glassRect = Rect()
    private val cigaretteRect = Rect()
    private var resizeRatioW = 0f
    private var resizeRatioH = 0f

    //    private lateinit var mFaceLandmarkPaint: Paint

    private val cropRgbBitmap by lazy {
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    // The glasses, cigarette bitmap
    private val glassesBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.glasses)
    }
    private val cigaretteBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.cigarette)
    }

    fun initialize() {
        try {
            val inputStream = context.assets.open("shape_predictor_68_face_landmarks.dat")
            val cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE)
            mCascadeFile = File(cascadeDir, "shape_predictor_68_face_landmarks.dat")
            if (mCascadeFile?.exists() == false) {
                val outputStream = FileOutputStream(mCascadeFile)
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                inputStream.close()
                outputStream.close()
            }
            mFaceDet = FaceDet(mCascadeFile?.absolutePath)
//            mFaceLandmarkPaint = Paint()
//            mFaceLandmarkPaint.color = Color.RED
//            mFaceLandmarkPaint.strokeWidth = 2f
//            mFaceLandmarkPaint.style = Paint.Style.STROKE
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load cascade. Exception thrown: $e")
        }
    }

    @Synchronized
    fun deInitialize() {
        mFaceDet?.release()
    }

    private fun drawResizedBitmap(src: Bitmap, dst: Bitmap) {
        val minDim = min(src.width, src.height).toFloat()
        val matrix = Matrix()
        // We only want the center square out of the original rectangle.
        val translateX = -max(0f, (src.width - minDim) / 2)
        val translateY = -max(0f, (src.height - minDim) / 2)
        matrix.preTranslate(translateX, translateY)
        val scaleFactor = dst.height / minDim
        matrix.postScale(scaleFactor, scaleFactor)
        // Rotate around the center if necessary.
        matrix.postTranslate(-dst.width / 2.0f, -dst.height / 2.0f)
        matrix.postTranslate(dst.width / 2.0f, dst.height / 2.0f)
        val canvas = Canvas(dst)
        canvas.drawBitmap(src, matrix, null)
    }

    private fun scaleBitmapFitRect(src: Bitmap, dst: Bitmap) {
        val matrix = Matrix()
        val transX =
            if (dst.width > src.width)
                (dst.width - src.width) / 2
            else
                (src.width - dst.width) / 2

        val tranY =
            if (dst.height > src.height)
                (dst.height - src.height) / 2
            else
                (src.height - dst.height) / 2

        matrix.setTranslate(transX.toFloat(), tranY.toFloat())

        val scaleFactor = if (dst.width > dst.height) {
            dst.width.toFloat() / src.width
        } else {
            dst.height.toFloat() / src.height
        }
        matrix.postScale(scaleFactor, scaleFactor, inputImageRect.exactCenterX(), inputImageRect.exactCenterY())
        val canvas = Canvas(dst)
        canvas.drawBitmap(src, matrix, null)
    }

    private fun rotate(bitmap: Bitmap, degree: Int, mirrored: Boolean): Bitmap {
        val matrix = decodeExifOrientation(computeExifOrientation(degree, mirrored))
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    fun onImageAvailable(image: Image, rotateDegree: Int, mirrored: Boolean): Bitmap? {
        if (surfaceHolder == null) {
            return null
        }
        cameraBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).let {
            yuvConverter.yuvToRgb(image, it)
            // Todo rotate Bitmap take huge resource, so find other way to rotate it.
            rotate(it, rotateDegree, mirrored)
        }

        scaleBitmapFitRect(cameraBitmap, bitmap)
        drawResizedBitmap(bitmap, cropRgbBitmap)

        val results = mFaceDet?.detect(cropRgbBitmap)
        // Draw on bitmap
        if (results != null) {
            val canvas = Canvas(bitmap)
            resizeRatioW = bitmap.width.toFloat() / cropRgbBitmap.width.toFloat()
            resizeRatioH = bitmap.height.toFloat() / cropRgbBitmap.height.toFloat()
            for (ret in results) {
                val landmarks = ret.faceLandmarks
                val eyeBrowLeft = landmarks[20]
                val topNose = landmarks[27]
                val bottomNose = landmarks[30]
                val leftEye = landmarks[36]
                val topLeftEyePoint = landmarks[38]
                val topRightEyePoint = landmarks[43]
                val rightEye = landmarks[45]
                val leftMouth = landmarks[67]
                val rightMouth = landmarks[64]

                //  draw all the landmark
//                landmarks.forEach {
//                    val pointX = it.x * resizeRatio
//                    val pointY = (it.y + marginBitmap) * resizeRatio
//                    canvas.drawCircle(pointX, pointY, 2F, mFaceLandmarkPaint)
//                }

                drawGlasses(
                    canvas,
                    leftEye,
                    rightEye,
                    topLeftEyePoint,
                    topRightEyePoint,
                    eyeBrowLeft.y,
                    topNose,
                    bottomNose
                )
                drawCigarette(canvas, leftMouth, rightMouth)
            }
        }
        surfaceHolder?.let { tryDrawing(it) }
        return bitmap
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        inputImageRect.set(0, 0, right, bottom)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        this.surfaceHolder = holder
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
    }

    private fun tryDrawing(holder: SurfaceHolder) {
        val canvas = holder.lockCanvas()
        if (canvas == null) {
            Log.e(TAG, "Cannot draw onto the canvas as it's null")
        } else {
            canvas.drawBitmap(bitmap, null, inputImageRect, null)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGlasses(
        canvas: Canvas,
        leftEye: Point,
        rightEye: Point,
        topLeftEyePoint: Point,
        topRightEyePoint: Point,
        eyeBrowLeft: Int,
        topNose: Point,
        bottomNose: Point
    ) {
        val eyeAndEyeBrowDistance = (topLeftEyePoint.y - eyeBrowLeft) / 2 * resizeRatioW
        val length = (topRightEyePoint.x - topLeftEyePoint.x).toDouble()
        val height = abs(topLeftEyePoint.y - topRightEyePoint.y).toDouble()
        var degrees = toDegrees(tan(height / length)).toFloat()
        if (topLeftEyePoint.y > topRightEyePoint.y) {
            degrees = -degrees
        }
        val topRect = topNose.y * resizeRatioH - eyeAndEyeBrowDistance
        glassRect.set(
            (leftEye.x * resizeRatioW - eyeAndEyeBrowDistance).toInt(),
            topRect.toInt(),
            (rightEye.x * resizeRatioW + eyeAndEyeBrowDistance).toInt(),
            (topRect + distanceBetweenPoints(topNose, bottomNose)).toInt()
        )
        canvas.drawRotateCanvas(glassesBitmap, degrees, glassRect)
    }

    private fun Canvas.drawRotateCanvas(bitmap: Bitmap, degrees: Float, rect: Rect) {
        save()
        rotate(degrees, rect.exactCenterX(), rect.exactCenterY())
        drawBitmap(
            bitmap,
            null,
            rect,
            null
        )
        restore()
    }

    private fun distanceBetweenPoints(
        p1: Point,
        p2: Point
    ): Double {
        val ac = abs(p2.y - p1.y)
        val cb = abs(p2.x - p1.x)
        return hypot(ac.toDouble(), cb.toDouble()) * resizeRatioW
    }

    private fun drawCigarette(canvas: Canvas, leftMouth: Point, rightMouth: Point) {
        val mouthLength = (rightMouth.x - leftMouth.x) * resizeRatioW
        cigaretteRect.set(
            (leftMouth.x * resizeRatioW - mouthLength).toInt(),
            (leftMouth.y * resizeRatioH).toInt(),
            (leftMouth.x * resizeRatioW).toInt(),
            (leftMouth.y * resizeRatioH + mouthLength).toInt()
        )
        canvas.drawBitmap(
            cigaretteBitmap,
            null,
            cigaretteRect,
            null
        )
    }

    companion object {

        private const val INPUT_SIZE = 224
    }
}