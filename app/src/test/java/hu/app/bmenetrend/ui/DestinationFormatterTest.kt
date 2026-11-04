package hu.app.bmenetrend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationFormatterTest {
    @Test
    fun formatsCompoundVolanHeadsignToLastDestination() {
        assertEquals("Etyek, Botpuszta", DestinationFormatter.clean("Budapest-Biatorbágy-Etyek Botpuszta"))
        assertEquals("Sóskút, Iskola", DestinationFormatter.clean("Budapest-Premier Outlet-Biatorbágy Vasútállomás-Sóskút Iskola"))
        assertEquals("Biatorbágy", DestinationFormatter.clean("Budapest-Biatorbágy"))
        // Resolves Budapest terminals accurately instead of bare "Budapest"
        assertEquals("Budapest, Kelenföld vasútállomás", DestinationFormatter.clean("Etyek Botpuszta-Biatorbágy-Budapest", "760"))
        assertEquals("Budapest, Kelenföld vasútállomás", DestinationFormatter.clean("Biatorbágy-Budapest", "762"))
        assertEquals("Budapest, Kelenföld vasútállomás", DestinationFormatter.clean("Biatorbágy-Budapest"))
        assertEquals("Budapest, Széll Kálmán tér", DestinationFormatter.clean("Zsámbék-Budapest", "782"))
        assertEquals("Budapest, Újpest-Városkapu", DestinationFormatter.clean("Vác-Budapest", "300"))
        assertEquals("Budapest, Népliget", DestinationFormatter.clean("Dabas-Budapest", "626"))
    }

    @Test
    fun preservesRailTerminalsWithHyphen() {
        assertEquals("Budapest-Déli", DestinationFormatter.clean("Budapest-Déli"))
        assertEquals("Budapest-Keleti", DestinationFormatter.clean("Budapest-Keleti"))
        assertEquals("Budapest-Nyugati", DestinationFormatter.clean("Budapest-Nyugati"))
        assertEquals("Budapest-Kelenföld", DestinationFormatter.clean("Budapest-Kelenföld"))
        assertEquals("Budapest-Déli", DestinationFormatter.clean("Győr - Budapest-Déli"))
        assertEquals("Budapest-Keleti", DestinationFormatter.clean("Herceghalom -> Budapest-Keleti"))
    }

    @Test
    fun formatsArrowsAndDashes() {
        assertEquals("Etyek, Botpuszta", DestinationFormatter.clean("Budapest - Biatorbágy - Etyek, Botpuszta"))
        assertEquals("Etyek", DestinationFormatter.clean("Budapest – Etyek"))
        assertEquals("Győr", DestinationFormatter.clean("Budapest → Győr"))
        assertEquals("Széll Kálmán tér M", DestinationFormatter.clean("Örs vezér tere -> Széll Kálmán tér M"))
    }

    @Test
    fun formatsTownAndStopWithoutComma() {
        assertEquals("Etyek, Botpuszta", DestinationFormatter.clean("Etyek Botpuszta"))
        assertEquals("Sóskút, Iskola", DestinationFormatter.clean("Sóskút Iskola"))
        assertEquals("Biatorbágy, Vasútállomás", DestinationFormatter.clean("Biatorbágy Vasútállomás"))
        assertEquals("Budapest, Kelenföld vasútállomás", DestinationFormatter.clean("Budapest Kelenföld vasútállomás"))
    }

    @Test
    fun leavesAlreadyCleanDestinationsIntact() {
        assertEquals("Etyek, Botpuszta", DestinationFormatter.clean("Etyek, Botpuszta"))
        assertEquals("Sóskút, Iskola", DestinationFormatter.clean("Sóskút, Iskola"))
        assertEquals("Budaörs, Benzinkút", DestinationFormatter.clean("Budaörs, Benzinkút"))
        assertEquals("Széll Kálmán tér M", DestinationFormatter.clean("Széll Kálmán tér M"))
        assertEquals("Herceghalom", DestinationFormatter.clean("Herceghalom"))
        assertEquals("", DestinationFormatter.clean(""))
    }
}
