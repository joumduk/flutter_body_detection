package com.u0x48lab.body_detection

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutionException

class CameraSession(private var context: Context) {
    private var processOutput: ((ImageProxy, Int) -> Unit)? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private val lifecycle = CustomLifecycle()
    private var screenSize:Size =Size(0,0)


    init {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            Runnable {
                try {
                    cameraProvider = cameraProviderFuture.get()

                    bindAnalysisUseCase()
                } catch (e: ExecutionException) {
                    // Handle any errors (including cancellation) here.
                    Log.e("CameraSession", "Unhandled exception", e)
                } catch (e: InterruptedException) {
                    Log.e("CameraSession", "Unhandled exception", e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }
    fun setScreenSize(width:Int,height:Int){
        if (width == null || height==null) return

        screenSize = Size(width,height)
        bindAnalysisUseCase()

    }
    private class CustomLifecycle : LifecycleOwner {
        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

        init {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun doOnResume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override fun getLifecycle(): Lifecycle {
            return lifecycleRegistry
        }
    }

    fun switchCamera(isFront: Boolean) {
        if (cameraProvider == null) return

        val newLensFacing = if (isFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        if (newLensFacing != lensFacing) {
            val newCameraSelector = CameraSelector.Builder().requireLensFacing(newLensFacing).build()
            try {
                if (cameraProvider!!.hasCamera(newCameraSelector)) {
                    lensFacing = newLensFacing
                    cameraSelector = newCameraSelector
                    bindAnalysisUseCase()
                    return
                }
            } catch (e: CameraInfoUnavailableException) {
                // Falls through
            }
        }
    }

    fun start(closure: (ImageProxy, Int) -> Unit) {
        processOutput = closure

        bindAnalysisUseCase()
        lifecycle.doOnResume()
    }

    fun stop() {
        unbindAnalysisUseCase()

        processOutput = null
    }

    private fun unbindAnalysisUseCase() {
        if (cameraProvider == null) return

        if (analysisUseCase != null) {
            cameraProvider!!.unbind(analysisUseCase)
        }
    }

    private fun bindAnalysisUseCase() {
        if (cameraProvider == null) return

        unbindAnalysisUseCase()
        val builder = ImageAnalysis.Builder()
        if(screenSize.width!=0 && screenSize.height!=0){
            builder .setTargetResolution(screenSize)
        }else{
            builder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }
//            .setTargetResolution(Size(1280, 720))
//            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        val useCase = builder.build()

        useCase.setAnalyzer(
            ContextCompat.getMainExecutor(context),
            ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
                val isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                processOutput?.let { it(imageProxy, rotationDegrees) }
            }
        )

        cameraProvider!!.bindToLifecycle(lifecycle, cameraSelector, useCase)

        analysisUseCase = useCase
    }
}
