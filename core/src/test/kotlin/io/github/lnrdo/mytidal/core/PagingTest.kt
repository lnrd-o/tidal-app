package io.github.lnrdo.mytidal.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PagingTest {
    /** A list of [size] items that the server gives in pages that are shorter than the 100 asked for. */
    private fun server(size: Int, pageSize: Int, reportTotal: Boolean): suspend (Int) -> PageOf<Int> = { offset ->
        PageOf((offset until minOf(offset + pageSize, size)).toList(), if (reportTotal) size else null)
    }

    @Test fun shortPagesDoNotEndTheList() = runBlocking {
        assertEquals((0 until 250).toList(), collectPages(page = server(250, 95, reportTotal = false)))
    }

    @Test fun theReportedTotalEndsTheListWithoutAnExtraRequest() = runBlocking {
        var requests = 0
        val inner = server(250, 100, reportTotal = true)
        val all = collectPages { offset -> requests++; inner(offset) }
        assertEquals(250, all.size); assertEquals(3, requests)
    }

    @Test fun anEmptyListAndAnEndlessOneStop() = runBlocking {
        assertEquals(0, collectPages(page = server(0, 100, false)).size)
        assertEquals(30, collectPages(limit = 3) { PageOf((0 until 10).toList(), null) }.size)
    }
}
