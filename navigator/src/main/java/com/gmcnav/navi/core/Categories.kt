package com.gmcnav.navi.core

/**
 * What users look for: search categories (chips) and the service / technical rooms that are hidden from categories and
 * search suggestions (they stay on the map and routable, a place card works, and the exact room number still finds them).
 * Decided by a room's own name — a number inside a big merged space by its own label, never by the functions of the whole
 * space (the press centre's «зона общественного питания» used to put its corridors and switchboards into «Питание»).
 */
object Categories {
    /** Chips in the search screen, in order: id, title. «Быт» (laundries, kitchens) exists in the boarding house only. */
    val CHIPS = listOf("wc" to "Туалеты", "lift" to "Лифты", "stair" to "Лестницы", "escalator" to "Эскалаторы", "entrance" to "Входы",
        "food" to "Питание", "hall" to "Залы", "meet" to "Переговорные", "home" to "Быт")

    private fun n(s: String) = s.lowercase().replace('ё', 'е').trim()
    private const val B = "(^|[^а-яa-z0-9])"           // word start (Cyrillic-safe)
    private const val E = "([^а-яa-z0-9]|$)"           // word end

    private val SERVICE = Regex(listOf(
        // power, engineering, IT
        "электрощит", "элекрощит", "щитов", "слаботоч", "насосн", "венткамер", "вентилятор", "вентиляц", "тепловой пункт", "${B}итп$E",
        "трансформатор", "дизельн", "генератор", "распредел", "распедел", "узел связи", "${B}кросс", "демаркац", "${B}атс$E", "${B}ввод",
        "серверн", "аппаратн", "пультов", "операторск", "диспетчерск", "${B}цпу$E", "машинный зал", "холодильн", "морозильн",
        "шахт", "${B}ниша", "техническ", "техзон", "помещение для оборудования", "люминесцент", "пожаротуш",
        "${B}кск$E", "${B}сс$E", "скест", "${B}тжк$E", "сукс",
        // cleaning, waste, storage
        "уборочн", "уборки", "${B}куи$E", "${B}пуи$E", "инвентар", "инвентор", "мусор", "отход", "дезинф", "дезсредств", "моющих",
        "кладов", "склад", "${B}скад", "подсобн", "бельев", "сервизн", "хранени",
        // kitchens' back of house, deliveries
        "моечн", "${B}мойк", "мытье", "${B}цех", "раздаточн", "доготовочн", "заготовочн", "остывочн", "обработк[а-я]* яиц", "резк[а-я]* хлеба",
        "нарезк", "просев", "приготовлени", "подготовк", "кейтеринг", "заведующ", "зав\\. ?производ", "экспедиц", "загрузк", "загрузочн",
    ).joinToString("|"))
    /** Circulation and empty spaces: hidden from suggestions, but entrances may lead through them. */
    private val CIRCULATION = Regex(listOf(
        "коридор", "тамбур", "циркуляц", "лифтов[а-я]* холл", "лестничн[а-я]* клетк", "^лестница$", "^чердак", "помещение без номера", "^проход$", "шкафчик",
    ).joinToString("|"))
    private val STAFF = Regex("персонал|служебн|сотрудник|официант")
    private val WC = Regex("сан\\.?\\s*уз(ел|лы)|санузел|уборн|туалет|${B}с/у")
    /** En-suite bathroom of a residential room (Рекордов 15: «Санузел 202» belongs to room 202): private. */
    private val PRIVATE_WC = Regex("^(сан\\.?\\s*узел|санузел)\\s*\\d{3}[а-я]?$")
    private val FOOD = Regex("обеденн|ресторан|кафе|буфет|${B}столовая$E|кофейн|кофе-брейк|фудкорт|${B}бар$E|${B}питани")
    private val HALL = Regex("${B}зал(а|ы)?$E|конференц|коференц|аудитори|амфитеатр|лекционн|пленарн|учебный класс")
    private val NOT_HALL = Regex("^(зона|холл|коридор|рекреац|помещение|комната|склад|офис)")
    private val MEET = Regex("переговорн")
    private val LAB = Regex("лаборатор|мастерск|студи")
    private val HOME = Regex("прачечн|гладильн|${B}кухн")
    private val KITCHEN = Regex("${B}кухн")

    /**
     * Technical room (engineering, storage, cleaning, kitchens' back of house, deliveries). A kitchen is the restaurant's back
     * of house in a public building, but the residents' shared kitchen in a residential one ([residential]: Рекордов 15).
     */
    fun isTechnical(name: String, residential: Boolean = false): Boolean {
        val s = n(name)
        return SERVICE.containsMatchIn(s) || (!residential && KITCHEN.containsMatchIn(s))
    }

    /** Service room (technical or circulation / empty space): not offered in categories or suggestions. */
    fun isService(name: String, residential: Boolean = false): Boolean = isTechnical(name, residential) || CIRCULATION.containsMatchIn(n(name))

    /** Category of a room by its own name: wc, food, hall, meet, lab, home, or "" (none). */
    fun of(name: String, residential: Boolean = false): String {
        val s = n(name)
        if (s.isEmpty() || isService(s, residential)) return ""
        val staff = STAFF.containsMatchIn(s)
        return when {
            WC.containsMatchIn(s) -> if (staff || PRIVATE_WC.matches(s)) "" else "wc"
            staff -> ""
            FOOD.containsMatchIn(s) -> "food"
            MEET.containsMatchIn(s) -> "meet"
            HALL.containsMatchIn(s) && !NOT_HALL.containsMatchIn(s) -> "hall"
            LAB.containsMatchIn(s) -> "lab"
            HOME.containsMatchIn(s) -> "home"
            else -> ""
        }
    }
}
