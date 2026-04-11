package cgluwxh.bilising.dlna

data class DlnaDevice(
    val name: String,
    val ipAddress: String,
    val controlUrl: String,
    val renderingControlUrl: String = "",
    val udn: String = ""
)
