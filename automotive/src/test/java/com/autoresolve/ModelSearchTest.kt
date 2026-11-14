package com.autoresolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSearchTest {

    private fun find(query: String, brand: String = "Hyundai") =
        matchModels(query, VehicleCatalog.getModels(brand).map { it.name })

    @Test
    fun firstSyllableSuggestsIoniqAndAvante() {
        val r = find("아")
        assertTrue(r.toString(), r.containsAll(listOf("아이오닉 5", "아이오닉 6", "아반떼")))
        assertEquals("아이오닉 5", r.first())
        assertTrue(r.size <= 8)
    }

    @Test
    fun trailingJamoStillMatches() {
        listOf("아ㅇ", "앙", "아이ㅇ", "아이오ㄴ", "아이온").forEach { q ->
            val r = find(q)
            assertTrue("$q → $r", r.isNotEmpty() && r.all { it.startsWith("아이오닉") })
        }
    }

    @Test
    fun chosungSearch() {
        val r = find("ㅇㅇㅇ")
        assertTrue(r.toString(), r.isNotEmpty() && r.all { it.startsWith("아이오닉") })
    }

    @Test
    fun englishAlias() {
        assertEquals("아이오닉 5", find("ioniq").first())
        assertEquals("아이오닉 5", find("IONIQ 5").first())
        assertEquals("아반떼", find("avante").first())
        assertEquals("캐스퍼", find("casper").first())
    }

    @Test
    fun emptyOrNumberOnlyGivesNoList() {
        assertEquals(emptyList<String>(), find(""))
        assertEquals(emptyList<String>(), find("  "))
        assertEquals(emptyList<String>(), find("5"))
    }

    @Test
    fun numberAfterNameNarrows() {
        assertEquals(listOf("아이오닉 5", "아이오닉 5 N"), find("아이오닉5"))
    }

    @Test
    fun casper() {
        assertEquals(listOf("캐스퍼", "캐스퍼 일렉트릭"), find("캐"))
    }

    @Test
    fun gvIsCaseInsensitive() {
        val r = find("gv", "Genesis")
        assertTrue(r.toString(), r.containsAll(listOf("GV70", "GV80")))
        assertTrue(r.all { it.startsWith("GV") })
        assertEquals(listOf("GV70", "GV70 전동화 모델"), find("gv70", "Genesis"))
        assertEquals(listOf("GV70", "GV70 전동화 모델"), find("지브이70", "Genesis"))
    }

    @Test
    fun prefixComesBeforeSubstring() {
        // "나" 는 "넥쏘"와 무관하고, "코나"(중간 일치)만 있다.
        assertEquals(listOf("코나", "코나 일렉트릭", "쏘나타"), find("나"))
        // 앞부분 일치(일렉트릭 없음) 없이 중간 일치만 있는 경우에도 결과가 나온다.
        assertTrue(find("일렉").contains("캐스퍼 일렉트릭"))
    }
}
