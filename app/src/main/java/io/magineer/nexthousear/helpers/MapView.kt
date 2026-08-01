/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import androidx.annotation.ColorInt
import android.location.Geocoder
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.magineer.nexthousear.NextHouseARActivity
import io.magineer.nexthousear.R
import java.util.Locale

class MapView(val activity: NextHouseARActivity, val googleMap: GoogleMap) {
  private val CAMERA_MARKER_COLOR: Int = Color.argb(255, 0, 255, 0)
  private val EARTH_MARKER_COLOR: Int = Color.argb(255, 125, 125, 125)

  var setInitialCameraPosition = false
  val cameraMarker = createMarker(CAMERA_MARKER_COLOR)
  var cameraIdle = true

  val earthMarker = createMarker(EARTH_MARKER_COLOR)
  val geocoder = Geocoder(activity, Locale.getDefault())
  private var showDetails = true
  private var showCrime = false
  private var showProperty = false
  private var showStation = false
  private var currentInfo: LocationInfo? = null

  init {
    googleMap.uiSettings.apply {
      isMapToolbarEnabled = false
      isIndoorLevelPickerEnabled = false
      isZoomControlsEnabled = false
      isTiltGesturesEnabled = false
      isScrollGesturesEnabled = false
    }

    googleMap.setOnMarkerClickListener { unused -> false }

    // Add listeners to keep track of when the GoogleMap camera is moving.
    googleMap.setOnCameraMoveListener { cameraIdle = false }
    googleMap.setOnCameraIdleListener { cameraIdle = true }
  }

  fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
    val position = LatLng(latitude, longitude)
    activity.runOnUiThread {
      // If the map is already in the process of a camera update, then don't move it.
      if (!cameraIdle) {
        return@runOnUiThread
      }
      cameraMarker.isVisible = true
      cameraMarker.position = position
      cameraMarker.rotation = heading.toFloat()

      val cameraPositionBuilder: CameraPosition.Builder = if (!setInitialCameraPosition) {
        // Set the camera position with an initial default zoom level.
        setInitialCameraPosition = true
        CameraPosition.Builder().zoom(21f).target(position)
      } else {
        // Set the camera position and keep the same zoom level.
        CameraPosition.Builder()
          .zoom(googleMap.cameraPosition.zoom)
          .target(position)
      }
      googleMap.moveCamera(
        CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()))
    }
  }

  fun setDetailsVisible(isVisible: Boolean) {
    activity.runOnUiThread {
      showDetails = isVisible
      if (isVisible) {
        refreshInfoWindow()
      } else {
        earthMarker.hideInfoWindow()
      }
    }
  }

  fun setCrimeVisible(isVisible: Boolean) {
    activity.runOnUiThread {
      showCrime = isVisible
      renderInfo()
    }
  }

  fun setPropertyVisible(isVisible: Boolean) {
    activity.runOnUiThread {
      showProperty = isVisible
      renderInfo()
    }
  }

  fun setStationVisible(isVisible: Boolean) {
    activity.runOnUiThread {
      showStation = isVisible
      renderInfo()
    }
  }

  fun updateInfo(
      title: String,
      neighborhood: String,
      borough: String,
      postcode: String,
      crimeCount: Int?,
      housingInfo: HousingInfo?,
      stationInfo: StationInfo?
  ) {
    activity.runOnUiThread {
        currentInfo = LocationInfo(
            title = title,
            neighborhood = neighborhood,
            borough = borough,
            postcode = postcode,
            crimeCount = crimeCount,
            housingInfo = housingInfo,
            stationInfo = stationInfo
        )
        renderInfo()
    }
  }

  private fun renderInfo() {
    val info = currentInfo ?: return
    earthMarker.title = info.title

    val snippetLines = mutableListOf<String>()
    val addressDetails = mutableListOf<String>()
    if (info.neighborhood.isNotEmpty() && info.neighborhood != info.title) {
      addressDetails.add(info.neighborhood)
    }
    if (info.borough.isNotEmpty() && info.borough != info.title) {
      addressDetails.add(info.borough)
    }
    if (info.postcode.isNotEmpty()) {
      addressDetails.add(info.postcode)
    }
    if (addressDetails.isNotEmpty()) {
      snippetLines.add(addressDetails.joinToString(", "))
    }

    if (showCrime) {
      info.crimeCount?.let {
        snippetLines.add("${crimeActivityLabel(it)} (${String.format(Locale.UK, "%,d", it)} reports)")
      }
    }
    if (showProperty) {
      info.housingInfo?.let {
        snippetLines.add("Sale Price: £${String.format(Locale.UK, "%,d", it.price)}")
        if (it.date.isNotBlank()) {
          snippetLines.add("Sale Date: ${it.date}")
        }
      }
    }

    if (showStation) {
      info.stationInfo?.let {
        snippetLines.add("Station: ${it.name} (${it.walkingMinutes}m walk)")
      }
    }

    earthMarker.snippet = snippetLines.joinToString("\n")
    refreshInfoWindow()
  }

  private fun refreshInfoWindow() {
    if (showDetails && earthMarker.isVisible) {
      earthMarker.hideInfoWindow()
      earthMarker.showInfoWindow()
    }
  }

  private fun crimeActivityLabel(count: Int): String {
    return when {
      count < 25 -> "Low crime activity"
      count < 100 -> "Moderate crime activity"
      count < 300 -> "High crime activity"
      else -> "Very high crime activity"
    }
  }

  private data class LocationInfo(
      val title: String,
      val neighborhood: String,
      val borough: String,
      val postcode: String,
      val crimeCount: Int?,
      val housingInfo: HousingInfo?,
      val stationInfo: StationInfo?
  )

  /** Creates and adds a 2D anchor marker on the 2D map view.  */
  private fun createMarker(
    color: Int,
  ): Marker {
    val markersOptions = MarkerOptions()
      .position(LatLng(0.0,0.0))
      .draggable(false)
      .anchor(0.5f, 0.5f)
      .flat(true)
      .visible(false)
      .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(color)))
    return googleMap.addMarker(markersOptions)!!
  }

  private fun createColoredMarkerBitmap(@ColorInt color: Int): Bitmap {
    val opt = BitmapFactory.Options()
    opt.inMutable = true
    val navigationIcon =
      BitmapFactory.decodeResource(activity.resources, R.drawable.ic_navigation_white_48dp, opt)
    val p = Paint()
    p.colorFilter = LightingColorFilter(color,  /* add= */1)
    val canvas = Canvas(navigationIcon)
    canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)
    return navigationIcon
  }
}
