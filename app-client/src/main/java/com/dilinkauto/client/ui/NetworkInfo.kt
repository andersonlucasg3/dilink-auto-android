package com.dilinkauto.client.ui

import com.dilinkauto.client.service.NetUtil

/** Network helpers for the phone UI (status card shows local IPs on port 9637). */
internal fun getLocalIpAddresses(): List<String> =
    NetUtil.localIpv4Addresses().map { "$it:9637" }

/*
 * Callers must NOT invoke [getLocalIpAddresses] on the composition thread
 * (audit A-L22): NetUtil walks every NetworkInterface, which is a blocking
 * syscall and janked the status card's first frame. MainScreen's StatusCard
 * now populates it from LaunchedEffect + withContext(Dispatchers.IO).
 */
