package ru.restofleet.console.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleTextsTest {
    private val now = 10 * 60_000L

    @Test
    fun `titles table`() {
        val rows = mapOf(
            "R-01" to "Устройство офлайн", "R-02" to "Низкий заряд", "R-03" to "Перегрев",
            "R-04" to "Рабочее приложение не на экране", "R-05" to "Цель недоступна", "R-06" to "Мало свободной памяти",
            "R-07" to "Сеть ресторана", "R-08" to "Бэкенд недоступен (все рестораны)",
            "R-09" to "Бэкенд недоступен (ресторан)", "R-99" to "R-99",
        )
        rows.forEach { (id, title) -> assertThat(RuleTexts.title(id)).isEqualTo(title) }
        assertThat(RuleTexts.title("R-05", "api")).isEqualTo("Цель api недоступна")
        assertThat(RuleTexts.title("R-05", " ")).isEqualTo("Цель недоступна")
    }

    @Test
    fun `details table`() {
        assertThat(RuleTexts.detail("R-01", mapOf("reason" to "lwt"), now)).isEqualTo("обрыв связи (LWT)")
        assertThat(RuleTexts.detail("R-01", mapOf("reason" to "keepalive"), now)).isEqualTo("нет keepalive")
        assertThat(RuleTexts.detail("R-01", mapOf("reason" to "disconnect"), now)).isEqualTo("отключился")
        assertThat(RuleTexts.detail("R-01", mapOf("reason" to "x"), now)).isEqualTo("x")
        assertThat(RuleTexts.detail("R-01", emptyMap(), now)).isEmpty()
        assertThat(RuleTexts.detail("R-01", mapOf("reason" to ""), now)).isEmpty()
        assertThat(RuleTexts.detail("R-02", mapOf("batteryLevel" to "18"), now)).isEqualTo("заряд 18 %")
        assertThat(RuleTexts.detail("R-02", emptyMap(), now)).isEmpty()
        assertThat(RuleTexts.detail("R-03", mapOf("tempC" to "46.8", "thermal" to "SEVERE"), now)).isEqualTo("46.8 °C · SEVERE")
        assertThat(RuleTexts.detail("R-03", mapOf("thermal" to "SEVERE"), now)).isEqualTo("SEVERE")
        assertThat(RuleTexts.detail("R-04", mapOf("pkg" to "com.android.chrome", "sinceTs" to "${now - 14 * 60_000}"), now))
            .isEqualTo("com.android.chrome · 14 мин")
        assertThat(RuleTexts.detail("R-04", mapOf("pkg" to "p", "sinceTs" to "${now + 5}"), now)).isEqualTo("p · 0 мин")
        assertThat(RuleTexts.detail("R-04", mapOf("pkg" to "p", "sinceTs" to "bad"), now)).isEqualTo("p")
        assertThat(RuleTexts.detail("R-05", mapOf("error" to "timeout", "failures" to "3"), now)).isEqualTo("timeout ×3")
        assertThat(RuleTexts.detail("R-05", mapOf("error" to "dns"), now)).isEqualTo("dns")
        assertThat(RuleTexts.detail("R-06", mapOf("freeMb" to "410"), now)).isEqualTo("свободно 410 МБ")
        assertThat(RuleTexts.detail("R-07", mapOf("x" to "y"), now)).isEmpty()
    }

    @Test
    fun `line joins title and detail`() {
        assertThat(RuleTexts.line("R-03", null, mapOf("tempC" to "46.8"), now)).isEqualTo("Перегрев · 46.8 °C")
        assertThat(RuleTexts.line("R-07", null, emptyMap(), now)).isEqualTo("Сеть ресторана")
    }
}
