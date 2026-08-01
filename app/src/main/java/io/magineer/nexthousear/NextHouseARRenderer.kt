/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import io.magineer.nexthousear.helpers.StationInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import io.magineer.nexthousear.helpers.ArInfoCardHelper
import io.magineer.nexthousear.helpers.HousingInfo
import io.magineer.nexthousear.common.helpers.DisplayRotationHelper
import io.magineer.nexthousear.common.helpers.TrackingStateHelper
import io.magineer.nexthousear.common.samplerender.Framebuffer
import io.magineer.nexthousear.common.samplerender.IndexBuffer
import io.magineer.nexthousear.common.samplerender.Mesh
import io.magineer.nexthousear.common.samplerender.SampleRender
import io.magineer.nexthousear.common.samplerender.Shader
import io.magineer.nexthousear.common.samplerender.Texture
import io.magineer.nexthousear.common.samplerender.VertexBuffer
import io.magineer.nexthousear.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer


class NextHouseARRenderer(val activity: NextHouseARActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "NextHouseARRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture
  lateinit var arCardMesh: Mesh
  lateinit var arCardShader: Shader
  lateinit var arCardTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model
  val cardModelMatrix = FloatArray(16)
  val cameraWorldMatrix = FloatArray(16)
  private val cardTextureLock = Object()
  private var pendingCardBitmap: Bitmap? = null
  @Volatile private var arCardVisible = false

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj");
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)

      arCardTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)
      arCardTexture.set(ArInfoCardHelper.createInfoCardBitmap("Selected location", listOf("Address selected")))
      arCardMesh = createCardMesh(render)
      arCardShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_card.vert",
          "shaders/ar_card.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", arCardTexture)

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

    val earth = session.earth
    if (earth?.trackingState == TrackingState.TRACKING) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )
      activity.view.updateStatusText(earth, cameraGeospatialPose)
    } else {
      earth?.let { activity.view.updateStatusText(it, null) }
    }

    // Draw the placed anchor, if it exists.
    earthAnchor?.let {
      render.renderCompassAtAnchor(it)
      render.renderInfoCardAtAnchor(it)
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  var earthAnchor: Anchor? = null
  private var locationRequestId = 0

  fun updateArInfoCard(title: String, lines: List<String>, visible: Boolean) {
    val bitmap = if (visible) ArInfoCardHelper.createInfoCardBitmap(title, lines) else null
    synchronized(cardTextureLock) {
      pendingCardBitmap?.recycle()
      pendingCardBitmap = bitmap
      arCardVisible = visible
    }
  }

  fun onMapClick(latLng: LatLng) {
    Log.d(TAG, "onMapClick at $latLng")
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }
    earthAnchor?.detach()
    // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
    val altitude = earth.cameraGeospatialPose.altitude - 1
    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f
    earthAnchor =
      earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)

    activity.view.mapView?.earthMarker?.apply {
      position = latLng
      isVisible = true
    }
    activity.view.updateArPropertyInfo(
        "Selected location",
        "",
        null,
        null,
        null,
        crimeLoading = true,
        housingLoading = true,
        stationLoading = true
    )
    
    // Start fetching data
    val requestId = ++locationRequestId
    fetchLocationDetails(latLng, requestId)
  }

  fun refreshLocationDetails(latLng: LatLng) {
      val requestId = ++locationRequestId
      fetchLocationDetails(latLng, requestId)
  }

  private fun fetchLocationDetails(latLng: LatLng, requestId: Int) {
    val view = activity.view
    val mapView = view.mapView ?: return
    
    // Run geocoding in a background thread
    kotlin.concurrent.thread {
        try {
            Log.d(TAG, "Fetching details for LatLng: $latLng")
            val addresses = mapView.geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (addresses?.isNotEmpty() == true) {
                val address = addresses[0]
                Log.d(TAG, "Geocoding success: $address")
                
                val neighborhood = address.subLocality ?: ""
                val street = address.thoroughfare ?: ""
                val borough = address.subAdminArea ?: ""
                val postcode = (address.postalCode ?: "").uppercase()
                
                Log.d(TAG, "Address details: Street=$street, Neighborhood=$neighborhood, Borough=$borough, Postcode=$postcode")
                
                // Better title for London: use the street or neighborhood
                val title = if (street.isNotEmpty()) street else if (neighborhood.isNotEmpty()) neighborhood else address.featureName ?: "Unknown"
                
                // State to collect all data
                var crimeResult: Int? = null
                var housingResult: HousingInfo? = null
                var stationResult: StationInfo? = null
                var crimeLoading = true
                var housingLoading = postcode.isNotEmpty()
                var stationLoading = true
                var crimeError: String? = null
                var housingError: String? = if (postcode.isEmpty()) "No postcode found" else null
                var stationError: String? = null

                // Initial update with geocoding results
                mapView.updateInfo(title, street, borough, postcode, null, null, null)
                view.updateArPropertyInfo(
                    title,
                    postcode,
                    crimeResult,
                    housingResult,
                    stationResult,
                    crimeLoading,
                    housingLoading,
                    stationLoading,
                    crimeError,
                    housingError,
                    stationError
                )
                
                // Police.uk returns street-level reports near the selected lat/lng.
                view.crimeDataHelper.fetchCrimeInfo(latLng.latitude, latLng.longitude) { result ->
                    if (requestId != locationRequestId) return@fetchCrimeInfo
                    Log.d(TAG, "Crime result received: $result")
                    crimeResult = result.count
                    crimeError = result.errorMessage
                    crimeLoading = false
                    mapView.updateInfo(title, street, borough, postcode, crimeResult, housingResult, stationResult)
                    view.updateArPropertyInfo(
                        title,
                        postcode,
                        crimeResult,
                        housingResult,
                        stationResult,
                        crimeLoading,
                        housingLoading,
                        stationLoading,
                        crimeError,
                        housingError,
                        stationError
                    )
                }
                
                // HM Land Registry Price Paid Data requires a postcode.
                if (postcode.isNotEmpty()) {
                    view.housingDataHelper.fetchLatestSoldPropertyResult(postcode) { result ->
                        if (requestId != locationRequestId) return@fetchLatestSoldPropertyResult
                        Log.d(TAG, "Housing result received: $result")
                        housingResult = result.info
                        housingError = result.errorMessage
                        housingLoading = false
                        mapView.updateInfo(title, street, borough, postcode, crimeResult, housingResult, stationResult)
                        view.updateArPropertyInfo(
                            title,
                            postcode,
                            crimeResult,
                            housingResult,
                            stationResult,
                            crimeLoading,
                            housingLoading,
                            stationLoading,
                            crimeError,
                            housingError,
                            stationError
                        )
                    }
                } else {
                    Log.w(TAG, "No postcode found for housing lookup")
                }

                // Fetch station data from TfL using LatLng
                view.transportDataHelper.fetchNearestStation(latLng.latitude, latLng.longitude) { info ->
                    if (requestId != locationRequestId) return@fetchNearestStation
                    Log.d(TAG, "Station info received: $info")
                    stationResult = info
                    stationLoading = false
                    stationError = if (info == null) "No station found within 1km" else null
                    mapView.updateInfo(title, street, borough, postcode, crimeResult, housingResult, stationResult)
                    view.updateArPropertyInfo(
                        title,
                        postcode,
                        crimeResult,
                        housingResult,
                        stationResult,
                        crimeLoading,
                        housingLoading,
                        stationLoading,
                        crimeError,
                        housingError,
                        stationError
                    )
                }
            } else if (requestId == locationRequestId) {
                view.updateArPropertyInfo(
                    "Selected location",
                    "",
                    null,
                    null,
                    null,
                    crimeLoading = false,
                    housingLoading = false,
                    stationLoading = false,
                    stationError = "No address found"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geocoding failed", e)
            if (requestId == locationRequestId) {
                view.updateArPropertyInfo(
                    "Selected location",
                    "",
                    null,
                    null,
                    null,
                    crimeLoading = false,
                    housingLoading = false,
                    stationLoading = false,
                    stationError = "Geocoding failed"
                )
            }
        }
    }
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    // Get the current pose of the Anchor in world space.
    anchor.pose.toMatrix(modelMatrix, 0)

    // 1. Render the 3D Marker (Compass/Pawn)
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun SampleRender.renderInfoCardAtAnchor(anchor: Anchor) {
    if (!arCardVisible || !::arCardMesh.isInitialized || !::arCardTexture.isInitialized) return

    synchronized(cardTextureLock) {
      pendingCardBitmap?.let {
        arCardTexture.set(it)
        it.recycle()
        pendingCardBitmap = null
      }
    }

    anchor.pose.toMatrix(modelMatrix, 0)
    Matrix.invertM(cameraWorldMatrix, 0, viewMatrix, 0)

    Matrix.setIdentityM(cardModelMatrix, 0)
    val widthMeters = 2.0f
    val heightMeters = 1.0f

    cardModelMatrix[0] = cameraWorldMatrix[0] * widthMeters
    cardModelMatrix[1] = cameraWorldMatrix[1] * widthMeters
    cardModelMatrix[2] = cameraWorldMatrix[2] * widthMeters
    cardModelMatrix[4] = cameraWorldMatrix[4] * heightMeters
    cardModelMatrix[5] = cameraWorldMatrix[5] * heightMeters
    cardModelMatrix[6] = cameraWorldMatrix[6] * heightMeters
    cardModelMatrix[8] = cameraWorldMatrix[8]
    cardModelMatrix[9] = cameraWorldMatrix[9]
    cardModelMatrix[10] = cameraWorldMatrix[10]
    cardModelMatrix[12] = modelMatrix[12]
    cardModelMatrix[13] = modelMatrix[13] + 1.2f
    cardModelMatrix[14] = modelMatrix[14]

    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, cardModelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
    arCardShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(arCardMesh, arCardShader, virtualSceneFramebuffer)
  }

  private fun createCardMesh(render: SampleRender): Mesh {
    val vertices = floatBufferOf(
      -0.5f, -0.5f, 0f,
      0.5f, -0.5f, 0f,
      -0.5f, 0.5f, 0f,
      0.5f, 0.5f, 0f
    )
    val texCoords = floatBufferOf(
      0f, 1f,
      1f, 1f,
      0f, 0f,
      1f, 0f
    )
    return Mesh(
      render,
      Mesh.PrimitiveMode.TRIANGLE_STRIP,
      null,
      arrayOf(
        VertexBuffer(render, 3, vertices),
        VertexBuffer(render, 2, texCoords)
      )
    )
  }

  private fun floatBufferOf(vararg values: Float): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
    buffer.put(values)
    buffer.rewind()
    return buffer
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
