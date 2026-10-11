package dev.edgeai.pickleball.camera

data class CameraCapabilities(
    val width: Int,
    val height: Int,
    val supportedFpsRanges: List<String>,
    val cameraFacing: String,
    val sensorOrientationDegrees: Int?,
    val deviceModel: String,
)