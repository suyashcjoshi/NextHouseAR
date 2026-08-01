/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.opengl.GLSurfaceView
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import io.magineer.nexthousear.NextHouseARActivity
import io.magineer.nexthousear.R
import io.magineer.nexthousear.common.helpers.SnackbarHelper
import okhttp3.OkHttpClient
import java.util.Locale

/** Contains UI elements for Next House AR. */
class NextHouseARView(val activity: NextHouseARActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

  private val httpClient = OkHttpClient()
  val crimeDataHelper = CrimeDataHelper(httpClient)
  val housingDataHelper = HousingDataHelper(httpClient)
  val transportDataHelper = TransportDataHelper(httpClient)

  val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
    setup { screenLocation ->
      val latLng: LatLng =
        mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
      activity.renderer.onMapClick(latLng)
    }
  }
  val mapFragment =
    (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
  }

  val statusText = root.findViewById<TextView>(R.id.statusText)
  private val arInfoPanel = root.findViewById<View>(R.id.arInfoPanel)
  private val arCrimeText = root.findViewById<TextView>(R.id.arCrimeText)
  private val arPropertyText = root.findViewById<TextView>(R.id.arPropertyText)
  private val arStationText = root.findViewById<TextView>(R.id.arStationText)
  private var latestArInfo: ArPropertyInfo? = null

  val cbShowDebug = root.findViewById<CheckBox>(R.id.cbShowDebug).apply {
      setOnCheckedChangeListener { _, isChecked ->
          statusText.visibility = if (isChecked) View.VISIBLE else View.GONE
      }
  }
  val cbShowDetails = root.findViewById<CheckBox>(R.id.cbShowDetails).apply {
      setOnCheckedChangeListener { _, isChecked ->
          mapView?.setDetailsVisible(isChecked)
          renderArInfo()
      }
  }
  val cbShowCrime = root.findViewById<CheckBox>(R.id.cbShowCrime).apply {
      setOnCheckedChangeListener { _, isChecked ->
          mapView?.setCrimeVisible(isChecked)
          renderArInfo()
      }
  }
  val cbShowProperty = root.findViewById<CheckBox>(R.id.cbShowProperty).apply {
      setOnCheckedChangeListener { _, isChecked ->
          mapView?.setPropertyVisible(isChecked)
          renderArInfo()
      }
  }
  val cbShowStation = root.findViewById<CheckBox>(R.id.cbShowStation).apply {
      setOnCheckedChangeListener { _, isChecked ->
          mapView?.setStationVisible(isChecked)
          renderArInfo()
      }
  }

  fun updateArPropertyInfo(
      title: String,
      postcode: String,
      crimeCount: Int?,
      housingInfo: HousingInfo?,
      stationInfo: StationInfo?,
      crimeLoading: Boolean,
      housingLoading: Boolean,
      stationLoading: Boolean,
      crimeError: String? = null,
      housingError: String? = null,
      stationError: String? = null
  ) {
    activity.runOnUiThread {
      latestArInfo = ArPropertyInfo(
          title = title,
          postcode = postcode,
          crimeCount = crimeCount,
          housingInfo = housingInfo,
          stationInfo = stationInfo,
          crimeLoading = crimeLoading,
          housingLoading = housingLoading,
          stationLoading = stationLoading,
          crimeError = crimeError,
          housingError = housingError,
          stationError = stationError
      )
      renderArInfo()
    }
  }

  private fun renderArInfo() {
    val info = latestArInfo
    if (info == null) {
      arInfoPanel.visibility = View.GONE
      arCrimeText.visibility = View.GONE
      arPropertyText.visibility = View.GONE
      arStationText.visibility = View.GONE
      activity.renderer.updateArInfoCard("", emptyList(), visible = false)
      return
    }

    val cardLines = mutableListOf<String>()
    if (cbShowDetails.isChecked) {
      if (info.postcode.isNotBlank()) {
        cardLines.add(info.postcode)
      } else {
        cardLines.add("Address selected")
      }
    }

    if (cbShowCrime.isChecked) {
      val lines = crimeLines(info)
      arCrimeText.text = lines.joinToString("\n")
      arCrimeText.visibility = View.VISIBLE
      cardLines.addAll(lines)
    } else {
      arCrimeText.visibility = View.GONE
    }

    if (cbShowProperty.isChecked) {
      val lines = propertyLines(info)
      arPropertyText.text = lines.joinToString("\n")
      arPropertyText.visibility = View.VISIBLE
      cardLines.addAll(lines)
    } else {
      arPropertyText.visibility = View.GONE
    }

    if (cbShowStation.isChecked) {
      val lines = stationLines(info)
      arStationText.text = lines.joinToString("\n")
      arStationText.visibility = View.VISIBLE
      cardLines.addAll(lines)
    } else {
      arStationText.visibility = View.GONE
    }

    arInfoPanel.visibility =
      if (arCrimeText.visibility == View.VISIBLE ||
          arPropertyText.visibility == View.VISIBLE ||
          arStationText.visibility == View.VISIBLE) {
        View.VISIBLE
      } else {
        View.GONE
      }
    activity.renderer.updateArInfoCard(
      info.title.ifBlank { "Selected location" },
      cardLines,
      cardLines.isNotEmpty()
    )
  }

  private fun crimeLines(info: ArPropertyInfo): List<String> {
    val lines = mutableListOf("Crime")
    when {
      info.crimeLoading -> lines.add("Checking nearby reports...")
      info.crimeCount != null -> {
        lines.add(crimeActivityLabel(info.crimeCount))
        lines.add("${String.format(Locale.UK, "%,d", info.crimeCount)} reports within about 1 mile")
        lines.add("Latest Police.uk month")
      }
      info.crimeError != null -> lines.add(info.crimeError)
      else -> lines.add("No crime data available")
    }
    return lines
  }

  private fun stationLines(info: ArPropertyInfo): List<String> {
    val lines = mutableListOf("Nearest Station")
    when {
      info.stationLoading -> lines.add("Checking nearby stations...")
      info.stationInfo != null -> {
        lines.add(info.stationInfo.name)
        lines.add("${String.format(Locale.UK, "%,d", info.stationInfo.distanceMeters)}m away")
        lines.add("About ${info.stationInfo.walkingMinutes} min walk")
      }
      info.stationError != null -> lines.add(info.stationError)
      else -> lines.add("No station found within 1km")
    }
    return lines
  }

  private fun propertyLines(info: ArPropertyInfo): List<String> {
    val lines = mutableListOf("Property")
    if (info.postcode.isNotBlank()) {
      lines.add(info.postcode)
    }

    when {
      info.housingLoading -> lines.add("Checking latest sale...")
      info.housingInfo != null -> {
        lines.add("Latest sale: £${String.format(Locale.UK, "%,d", info.housingInfo.price)}")
        if (info.housingInfo.date.isNotBlank()) {
          lines.add(info.housingInfo.date)
        }
      }
      info.housingError != null -> lines.add(info.housingError)
      else -> lines.add("No sold-property record")
    }
    return lines
  }

  private fun crimeActivityLabel(count: Int): String {
    return when {
      count < 25 -> "Low nearby activity"
      count < 100 -> "Moderate nearby activity"
      count < 300 -> "High nearby activity"
      else -> "Very high nearby activity"
    }
  }

  fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
    activity.runOnUiThread {
      val poseText = if (cameraGeospatialPose == null) "" else
        activity.getString(R.string.geospatial_pose,
                           cameraGeospatialPose.latitude,
                           cameraGeospatialPose.longitude,
                           cameraGeospatialPose.horizontalAccuracy,
                           cameraGeospatialPose.altitude,
                           cameraGeospatialPose.verticalAccuracy,
                           cameraGeospatialPose.heading,
                           cameraGeospatialPose.headingAccuracy)
      statusText.text = activity.resources.getString(R.string.earth_state,
                                                     earth.earthState.toString(),
                                                     earth.trackingState.toString(),
                                                     poseText)
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }

  private data class ArPropertyInfo(
      val title: String,
      val postcode: String,
      val crimeCount: Int?,
      val housingInfo: HousingInfo?,
      val stationInfo: StationInfo?,
      val crimeLoading: Boolean,
      val housingLoading: Boolean,
      val stationLoading: Boolean,
      val crimeError: String?,
      val housingError: String?,
      val stationError: String?
  )
}
