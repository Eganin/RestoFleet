package ru.restofleet.console.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import ru.restofleet.console.root.RootComponent
import ru.restofleet.console.root.RootContent
import java.io.File

private const val FRAME_NANOS = 16_000_000L
private const val SETTLE_FRAMES = 10

/**
 * Рисует корень вне экрана и сохраняет PNG (реш. 422): валидация без вывода окна на передний план.
 * Несколько кадров подряд — чтобы успели отработать запуск компонентов и анимации.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun renderScreenshot(root: RootComponent, target: File, width: Int, height: Int, dark: Boolean = false) {
    val scene = ImageComposeScene(
        width = (width * SCALE).toInt(),
        height = (height * SCALE).toInt(),
        density = Density(SCALE),
    ) {
        RootContent(root, dark = dark)
    }
    try {
        var image = scene.render(0)
        repeat(SETTLE_FRAMES) { image = scene.render((it + 1) * FRAME_NANOS) }
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }
        target.absoluteFile.parentFile?.mkdirs()
        target.writeBytes(data.bytes)
    } finally {
        scene.close()
    }
}

private const val SCALE = 2f
