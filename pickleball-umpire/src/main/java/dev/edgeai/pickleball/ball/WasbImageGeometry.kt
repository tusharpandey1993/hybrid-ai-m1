package dev.edgeai.pickleball.ball

data class WasbAffineTransform(
    val scale: Double,
    val translateX: Double,
    val translateY: Double,
    val sourceOffsetX: Double,
    val sourceOffsetY: Double,
) {
    fun modelXToCamera(x: Int, modelWidth: Int): Float =
        ((x + 0.5) / scale + sourceOffsetX).toFloat()

    fun modelYToCamera(y: Int, modelHeight: Int): Float =
        ((y + 0.5) / scale + sourceOffsetY).toFloat()
}

object WasbImageGeometry {
    fun transform(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int = WasbModelContract.WIDTH,
        targetHeight: Int = WasbModelContract.HEIGHT,
    ): WasbAffineTransform {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth > 0 && targetHeight > 0)
        val scale = targetWidth.toDouble() / maxOf(sourceWidth, sourceHeight)
        val translateX = targetWidth / 2.0 - sourceWidth / 2.0 * scale
        val translateY = targetHeight / 2.0 - sourceHeight / 2.0 * scale
        return WasbAffineTransform(
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            sourceOffsetX = -translateX / scale,
            sourceOffsetY = -translateY / scale,
        )
    }
}