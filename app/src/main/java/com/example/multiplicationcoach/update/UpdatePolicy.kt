package com.example.multiplicationcoach.update

enum class UpdateNetwork { Wifi, Cellular, Offline }

enum class UpdateDecision { DownloadNow, WaitForUser }

object UpdatePolicy {
    fun decide(automatic: Boolean, network: UpdateNetwork): UpdateDecision =
        if (automatic && network == UpdateNetwork.Wifi) UpdateDecision.DownloadNow else UpdateDecision.WaitForUser
}
