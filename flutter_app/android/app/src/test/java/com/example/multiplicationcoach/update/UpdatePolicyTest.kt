package com.example.multiplicationcoach.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun automaticallyDownloadsOnlyOverWifi() {
        assertEquals(UpdateDecision.DownloadNow, UpdatePolicy.decide(automatic = true, network = UpdateNetwork.Wifi))
        assertEquals(UpdateDecision.WaitForUser, UpdatePolicy.decide(automatic = true, network = UpdateNetwork.Cellular))
        assertEquals(UpdateDecision.WaitForUser, UpdatePolicy.decide(automatic = true, network = UpdateNetwork.Offline))
    }

    @Test
    fun manualChecksNeverStartADownloadWithoutTheUserTap() {
        assertEquals(UpdateDecision.WaitForUser, UpdatePolicy.decide(automatic = false, network = UpdateNetwork.Wifi))
    }
}
