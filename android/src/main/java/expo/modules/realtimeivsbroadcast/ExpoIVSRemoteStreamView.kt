package expo.modules.realtimeivsbroadcast

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.amazonaws.ivs.broadcast.BroadcastConfiguration.AspectMode
import com.amazonaws.ivs.broadcast.Device
import com.amazonaws.ivs.broadcast.ImageDevice
import com.amazonaws.ivs.broadcast.ImageDeviceFrame
import com.amazonaws.ivs.broadcast.ImagePreviewSurfaceView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

@RequiresApi(Build.VERSION_CODES.P)
class ExpoIVSRemoteStreamView(context: Context, appContext: AppContext) : ExpoView(context, appContext), PiPStateListener {
    // React Native never re-measures natively added children on its own
    // (facebook/react-native#17968): a surface added after the first layout pass
    // stayed 0x0, which is the black screen after returning from PiP. Have
    // ExpoView measure and lay out our children after every requestLayout().
    override val shouldUseAndroidLayout: Boolean = true

    // Use SurfaceView instead of TextureView for better PiP performance
    private var ivsSurfaceView: ImagePreviewSurfaceView? = null
    // Second preview of the same device, hosted in the Activity's content view
    // only while in PiP (see addPipOverlaySurface).
    private var pipOverlaySurface: ImagePreviewSurfaceView? = null
    private var currentImageDevice: ImageDevice? = null
    private var stageManager: IVSStageManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Retry configuration
    private var retryCount = 0
    private val maxRetries = 10
    private val retryDelayMs = 200L

    // The only state this view knows is what it's currently showing
    var currentRenderedDeviceUrn: String? = null
        private set

    // Props
    private var scaleMode: String = "fill"
    
    // PiP registration flag
    private var isRegisteredForPiP = false
    
    // Frame monitoring for PiP
    private var lastFrameTime: Long = 0
    private var frameCount: Long = 0
    private var isInPiPMode = false

    init {
        Log.i("ExpoIVSRemoteStreamView", "Initializing Remote Stream View...")
        resolveStageManagerWithRetry()
    }
    
    // MARK: - PiPStateListener
    
    override fun onEnterPiP() {
        isInPiPMode = true
        Log.i("ExpoIVSRemoteStreamView", "📺 Entered PiP mode - keeping surface active")
        
        // Ensure view stays visible and rendering
        ivsSurfaceView?.let { surface ->
            surface.visibility = View.VISIBLE
            surface.keepScreenOn = true
        }

        addPipOverlaySurface()
    }

    /**
     * The PiP window resizes the whole Activity, but React Native's layout did
     * not reliably follow (it stayed at the PiP size from the first resize, so
     * the video showed cropped to its top-left and left white gaps after the
     * user enlarged the window). While in PiP, show a second preview of the same
     * device directly in the Activity's content view, where plain Android layout
     * fills whatever size the window currently has. The original surface stays
     * untouched in this view, so there is nothing to restore on exit (moving the
     * original between parents left it black: the IVS view does not rebind after
     * its surface is recreated).
     */
    private fun addPipOverlaySurface() {
        if (pipOverlaySurface != null) return
        val device = currentImageDevice ?: return
        val content = appContext.currentActivity?.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            Log.w("ExpoIVSRemoteStreamView", "No activity content view; PiP uses the in-place surface")
            return
        }
        try {
            val overlay = device.getPreviewSurfaceView(aspectModeFor(scaleMode))
            overlay.setZOrderOnTop(false)
            overlay.setZOrderMediaOverlay(true)
            content.addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            pipOverlaySurface = overlay
            Log.i("ExpoIVSRemoteStreamView", "📺 Added PiP overlay surface in activity content")
        } catch (e: Exception) {
            Log.w("ExpoIVSRemoteStreamView", "Could not add PiP overlay surface: ${e.message}")
        }
    }

    private fun removePipOverlaySurface() {
        pipOverlaySurface?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            Log.i("ExpoIVSRemoteStreamView", "📺 Removed PiP overlay surface")
        }
        pipOverlaySurface = null
    }

    private fun aspectModeFor(mode: String): AspectMode = when (mode.lowercase()) {
        "fit" -> AspectMode.FIT
        else -> AspectMode.FILL
    }
    
    override fun onExitPiP() {
        isInPiPMode = false
        Log.i("ExpoIVSRemoteStreamView", "📺 Exited PiP mode - refreshing stream")
        
        ivsSurfaceView?.keepScreenOn = false

        removePipOverlaySurface()
        // The in-place surface was never touched; just make sure it is laid out.
        requestLayout()
    }
    
    /**
     * Refresh the stream by re-attaching it
     * Called after exiting PiP to fix potential surface rendering issues
     */
    private fun refreshStream() {
        val device = currentImageDevice as? Device
        val urn = currentRenderedDeviceUrn
        
        if (device != null && urn != null) {
            Log.i("ExpoIVSRemoteStreamView", "🔄 Refreshing stream for device: $urn")
            
            // Clear current URN to force re-render
            currentRenderedDeviceUrn = null
            
            // Re-render the stream
            renderStream(device)
        } else {
            Log.w("ExpoIVSRemoteStreamView", "🔄 Cannot refresh - no device or URN available")
        }
    }
    
    /**
     * Register this view as the PiP source view
     * Called when this view starts rendering a stream
     */
    private fun registerForPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isRegisteredForPiP) {
            val pipManager = PictureInPictureManager.getInstance()
            pipManager.setSourceView(this)
            pipManager.addStateListener(this)
            isRegisteredForPiP = true
            Log.i("ExpoIVSRemoteStreamView", "📺 Registered as PiP source view")
        }
    }
    
    /**
     * Unregister this view from PiP
     */
    private fun unregisterFromPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isRegisteredForPiP) {
            val pipManager = PictureInPictureManager.getInstance()
            pipManager.removeStateListener(this)
            pipManager.setSourceView(null)
            isRegisteredForPiP = false
            Log.i("ExpoIVSRemoteStreamView", "📺 Unregistered from PiP source view")
        }
    }
    
    /**
     * Set up frame callback to monitor frame flow
     */
    private fun setupFrameCallback(imageDevice: ImageDevice) {
        imageDevice.setOnFrameCallback { frame: ImageDeviceFrame ->
            frameCount++
            lastFrameTime = System.currentTimeMillis()
            
            // Log frame info periodically (every 60 frames ~2 seconds at 30fps)
            if (frameCount % 60 == 0L) {
                Log.d("ExpoIVSRemoteStreamView", "📹 Frame #$frameCount received, size: ${frame.size.x}x${frame.size.y}, inPiP: $isInPiPMode")
            }
        }
        Log.i("ExpoIVSRemoteStreamView", "📹 Frame callback set up for device: ${imageDevice.descriptor.urn}")
    }
    
    /**
     * Clear frame callback
     */
    private fun clearFrameCallback() {
        currentImageDevice?.setOnFrameCallback(null)
        Log.d("ExpoIVSRemoteStreamView", "📹 Frame callback cleared")
    }
    
    private fun resolveStageManagerWithRetry() {
        Log.d("ExpoIVSRemoteStreamView", "Attempting to resolve StageManager singleton (attempt ${retryCount + 1})...")
        
        val manager = IVSStageManager.instance
        
        if (manager == null) {
            if (retryCount < maxRetries) {
                retryCount++
                Log.w("ExpoIVSRemoteStreamView", "IVSStageManager not ready, retrying in ${retryDelayMs}ms...")
                mainHandler.postDelayed({ resolveStageManagerWithRetry() }, retryDelayMs)
            } else {
                Log.e("ExpoIVSRemoteStreamView", "IVSStageManager singleton is null after $maxRetries attempts.")
            }
            return
        }
        
        this.stageManager = manager
        Log.d("ExpoIVSRemoteStreamView", "StageManager instance assigned. Registering view...")
        manager.registerRemoteView(this)
    }

    // This is the command the manager will issue to this view
    fun renderStream(device: Device) {
        // Ensure we're on the main thread for UI operations
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { renderStream(device) }
            return
        }
        
        if (device.descriptor.urn == this.currentRenderedDeviceUrn) {
            Log.d("ExpoIVSRemoteStreamView", "Already rendering device: ${device.descriptor.urn}")
            return // Already rendering this device
        }

        cleanupStreamView()

        try {
            Log.i("ExpoIVSRemoteStreamView", "Attempting to render stream for device: ${device.descriptor.urn}")
            
            val imageDevice = device as? ImageDevice
            if (imageDevice == null) {
                Log.e("ExpoIVSRemoteStreamView", "Device is not an ImageDevice: ${device.descriptor.type}")
                return
            }
            
            // Use SurfaceView instead of TextureView for better PiP performance
            // SurfaceView renders to its own window which works better when Activity enters PiP
            val aspectMode = when (scaleMode.lowercase()) {
                "fill" -> AspectMode.FILL
                "fit" -> AspectMode.FIT
                else -> AspectMode.FILL
            }
            
            val newSurfaceView = try {
                imageDevice.getPreviewSurfaceView(aspectMode)
            } catch (e: Exception) {
                Log.w("ExpoIVSRemoteStreamView", "getPreviewSurfaceView failed, trying previewView: ${e.message}")
                // Fall back to TextureView if SurfaceView fails
                null
            }
            
            if (newSurfaceView != null) {
                newSurfaceView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                
                // SurfaceView needs to be on top for PiP
                newSurfaceView.setZOrderOnTop(false)
                newSurfaceView.setZOrderMediaOverlay(true)
                
                this.ivsSurfaceView = newSurfaceView
                this.currentImageDevice = imageDevice
                this.currentRenderedDeviceUrn = device.descriptor.urn
                addView(newSurfaceView)
                if (isInPiPMode) addPipOverlaySurface()
                
                // Set up frame callback for monitoring
                setupFrameCallback(imageDevice)
                
                // Register as PiP source view
                registerForPiP()
                
                Log.i("ExpoIVSRemoteStreamView", "✅ Successfully rendering stream with SurfaceView for device: ${device.descriptor.urn}")
            } else {
                Log.e("ExpoIVSRemoteStreamView", "Failed to get preview surface view from ImageDevice")
            }
        } catch (e: Exception) {
            Log.e("ExpoIVSRemoteStreamView", "❌ Failed to render stream: ${e.message}", e)
            cleanupStreamView()
        }
    }
    
    fun clearStream() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { clearStream() }
            return
        }
        cleanupStreamView()
    }

    private fun cleanupStreamView() {
        // Clear frame callback first
        clearFrameCallback()
        
        removePipOverlaySurface()
        ivsSurfaceView?.let { surface ->
            Log.d("ExpoIVSRemoteStreamView", "Cleaning up stream view")
            (surface.parent as? ViewGroup)?.removeView(surface)
            ivsSurfaceView = null
        }
        currentImageDevice = null
        currentRenderedDeviceUrn = null
        frameCount = 0
        lastFrameTime = 0
    }

    private fun applyProps() {
        setScaleMode(this.scaleMode)
    }

    fun setScaleMode(mode: String) {
        this.scaleMode = mode
        val aspectMode = when (scaleMode.lowercase()) {
            "fill" -> AspectMode.FILL
            "fit" -> AspectMode.FIT
            // Unknown fills, matching iOS's ivsAspectMode and the declared default.
            else -> AspectMode.FILL
        }
        try {
            // Try to set aspect mode on SurfaceView
            ivsSurfaceView?.let { surface ->
                val method = surface.javaClass.getMethod("setPreviewAspectMode", AspectMode::class.java)
                method.invoke(surface, aspectMode)
            }
        } catch (e: Exception) {
            Log.w("ExpoIVSRemoteStreamView", "Could not set aspect mode: ${e.message}")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("ExpoIVSRemoteStreamView", "View attached to window, currentURN: $currentRenderedDeviceUrn")
        
        // Re-register if we lost the manager reference
        if (stageManager == null) {
            retryCount = 0
            resolveStageManagerWithRetry()
        } else {
            // Already have manager - re-register to get stream assigned
            // This handles the case when returning from navigation or PiP mode
            Log.d("ExpoIVSRemoteStreamView", "Re-registering with existing manager")
            stageManager?.registerRemoteView(this)
            
            // If no stream was assigned immediately, retry after a short delay
            // This handles timing issues when view re-attaches after navigation
            if (currentRenderedDeviceUrn == null) {
                mainHandler.postDelayed({
                    if (currentRenderedDeviceUrn == null && stageManager != null) {
                        Log.d("ExpoIVSRemoteStreamView", "Delayed retry: re-registering for stream assignment")
                        stageManager?.registerRemoteView(this)
                    }
                }, 100)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d("ExpoIVSRemoteStreamView", "View detached from window, clearing currentURN: $currentRenderedDeviceUrn")
        // Remove any pending retries
        mainHandler.removeCallbacksAndMessages(null)
        
        // Clear the rendered URN BEFORE unregistering so the stream becomes available
        val urnToClear = currentRenderedDeviceUrn
        currentRenderedDeviceUrn = null
        
        stageManager?.unregisterRemoteView(this)
        unregisterFromPiP()
        cleanupStreamView()
        
        Log.d("ExpoIVSRemoteStreamView", "View detached cleanup complete, cleared URN: $urnToClear")
    }
}
