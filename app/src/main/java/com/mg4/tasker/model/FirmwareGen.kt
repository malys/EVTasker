package com.mg4.tasker.model

/**
 * MG4 firmware generations, mirroring `FirmwareInfo.Gen` in MG4Control.
 *
 * The names match the strings MG4Control sends in the snapshot
 * ([com.mg4.tasker.bridge.BridgeContract.KEY_FIRMWARE_GEN]), so
 * `FirmwareGen.valueOf(snapshotString)` round-trips.
 */
enum class FirmwareGen { SWI133, SWI132, SWI68, SWI69, SWI131, SWI165 }
