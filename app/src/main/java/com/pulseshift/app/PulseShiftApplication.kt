package com.pulseshift.app

import android.app.Application
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.BlePulseShiftRepository
import com.pulseshift.app.data.repository.PulseShiftRepository

class PulseShiftApplication : Application() {
    lateinit var dataStore: PulseShiftDataStore
        private set

    lateinit var bleManager: PulseShiftBleManager
        private set

    lateinit var repository: PulseShiftRepository
        private set

    lateinit var bleRepository: BlePulseShiftRepository
        private set

    override fun onCreate() {
        super.onCreate()
        dataStore = PulseShiftDataStore(this)
        bleManager = PulseShiftBleManager(this)

        bleRepository = BlePulseShiftRepository(bleManager, dataStore)
        repository = bleRepository
    }
}
