package com.mg4.control.api;

/**
 * MG4Control's external control API — driving profiles. Client-side copy.
 *
 * MG4Tasker is an independent system app: it reads and writes the vehicle itself through
 * the shared MG4Hardware layer. The one thing it cannot do on its own is apply an
 * MG4Control *profile* (those live in MG4Control), so it binds this API and calls exactly
 * that. There is no vehicle read or raw property write here.
 *
 * This file must stay byte-identical to MG4Control's declaration; the AIDL Stub/Proxy is
 * matched by package + interface name at bind time.
 *
 * Guarded by the signature permission com.mg4.control.permission.CONTROL_PROFILES.
 */
interface IProfileControl {

    /** MG4Control profiles. Bundle: "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /**
     * Applies a whole profile. Bundle result: "ok" boolean, "verdict" String
     * (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     * "detail" String optional.
     */
    Bundle applyProfile(String profileId);

    /**
     * Shows MG4Control's profile picker overlay and leaves the choice to the driver.
     * Same result keys as applyProfile. Refused while the car moves or when its speed is
     * unreadable — the picker exists to apply a profile — and UNSUPPORTED when MG4Control
     * holds no profile to offer.
     */
    Bundle showProfilePicker();
}
