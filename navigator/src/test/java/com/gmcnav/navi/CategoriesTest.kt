package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.Categories
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search categories of both sites: each chip holds what its name says; service / technical rooms are in no category and
 * not suggested by words, but the exact number still finds them and they stay routable. Report: build/validation/categories.md
 */
class CategoriesTest {
    private val assets = File("src/main/assets")
    private val sites = listOf("ГМЦ" to Building(FileAssets(assets)), "Рекордов 15" to Building(PrefixAssets(FileAssets(assets), "sites/rekordov15/")))
    private val technical = Regex("электрощит|элекрощит|моечн|загрузк|слаботоч|насосн|венткамер|кладов|подсобн|уборк|уборочн|мусор|тамбур|шахт|коридор|склад|${"(^|[^а-я])"}цех|кух|раздаточн|приготовлени|аппаратн|серверн|техническ|холодильн|трансформатор", RegexOption.IGNORE_CASE)
    private val sb = StringBuilder("# Категории поиска\n")
    private var ok = true
    private fun check(n: String, c: Boolean, info: String = "") { sb.append("- ${if (c) "OK" else "FAIL"} $n${if (info.isNotEmpty()) " — $info" else ""}\n"); ok = ok && c }

    @Test
    fun categories() {
        for ((name, b) in sites) {
            val s = Search(b)
            sb.append("\n## $name\n\n| категория | шт. |\n|---|---|\n")
            val lists = Categories.CHIPS.associate { (id, t) -> t to s.category(id, limit = 10_000) }
            for ((t, l) in lists) sb.append("| $t | ${l.size} |\n")
            sb.append("\n")
            for ((t, l) in lists) {
                val bad = l.filter { technical.containsMatchIn(it.title) && !it.title.contains("Обеденн") && !(t == "Быт" && it.title.startsWith("Кухня")) }
                check("$name / $t: no service or technical rooms", bad.isEmpty(), bad.take(5).joinToString { it.title })
            }
            check("$name / «Быт»: kitchens only in the residential building", lists.getValue("Быт").none { it.title.contains("Кухня") } || name == "Рекордов 15")
            val food = lists.getValue("Питание")
            check("$name / Питание: dining halls, cafés, buffets only", food.all { Regex("обеденн|ресторан|кафе|буфет|кофейн|кофе-брейк|бар|столовая|питани", RegexOption.IGNORE_CASE).containsMatchIn(it.title) },
                food.joinToString { it.title.substringAfter(" · ") }.take(400))
            check("$name / Питание has the dining hall", food.any { it.title.contains("Обеденный зал") })
            check("$name / Переговорные are meeting rooms", lists.getValue("Переговорные").all { it.title.contains("ереговорн") })
            check("$name / Туалеты: no staff or private en-suite bathrooms",
                lists.getValue("Туалеты").none { Regex("персонал|служебн|^Санузел \\d{3}", RegexOption.IGNORE_CASE).containsMatchIn(it.title) })
            check("$name / Залы are halls and auditoriums", lists.getValue("Залы").all { Regex("зал|аудитор|класс|амфитеатр", RegexOption.IGNORE_CASE).containsMatchIn(it.title) },
                lists.getValue("Залы").filter { !Regex("зал|аудитор|класс|амфитеатр", RegexOption.IGNORE_CASE).containsMatchIn(it.title) }.joinToString { it.title })
            // words do not suggest service rooms; the exact number does; they stay routable
            for (w in listOf("электрощитовая", "моечная", "кладовая", "коридор", "насосная", "венткамера", "тамбур", "шахта")) {
                val r = s.query(w, limit = 200)
                check("$name: «$w» suggests nothing technical", r.none { it.service }, r.take(3).joinToString { it.title })
            }
            val svc = s.items.filter { it.service && it.rank > 0 }
            val withNum = svc.firstOrNull { it.title.first().isDigit() }
            if (withNum != null) {
                val num = withNum.title.substringBefore(" ")
                val r = s.query(num)
                check("$name: exact number «$num» still finds «${withNum.title}»", r.firstOrNull()?.title == withNum.title)
                check("$name: «${withNum.title}» is routable", withNum.mk().goals.isNotEmpty())
            }
            // what each category holds now
            for ((t, l) in lists) if (l.isNotEmpty()) sb.append("**$t** (${l.size}): ${l.map { it.title }.groupingBy { it.replace(Regex("^[\\dА-ЯA-Z.\\-]+ · "), "").replace(Regex("\\d+"), "N") }.eachCount().entries.sortedByDescending { it.value }.joinToString { if (it.value > 1) "${it.key} ×${it.value}" else it.key }}\n\n")
            // what is hidden now (service names, grouped)
            val hidden = svc.map { it.title.replace(Regex("^[\\dА-Яа-яA-Z.\\-]+ · "), "").replace(Regex("\\d+"), "N").trim() }.groupingBy { it }.eachCount()
            sb.append("**Скрыто из категорий и подсказок** (${svc.size} мест, ${hidden.size} названий): ${hidden.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }}\n")
        }
        println(sb)
        File("build/validation").also { it.mkdirs() }.resolve("categories.md").writeText(sb.toString())
        assertTrue("categories: see build/validation/categories.md", ok)
    }
}
