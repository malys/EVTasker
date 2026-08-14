package com.evsuite.profile.api;

/**
 * EVProfile's external control API — driving profiles. Client-side copy.
 *
 * EVTasker is an independent system app: it reads and writes the vehicle itself through
 * the shared EVHardware layer. The one thing it cannot do on its own is apply an
 * EVProfile *profile* (those live in EVProfile), so it binds this API and calls exactly
 * that. There is no vehicle read or raw property write here.
 *
 * This file must stay byte-identical to EVProfile's declaration; the AIDL Stub/Proxy is
 * matched by package + interface name at bind time.
 *
 * Guarded by the signature permission com.evsuite.profile.permission.CONTROL_PROFILES.
 */
interface IProfileControl {

    /** EVProfile profiles. Bundle: "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /**
     * Applies a whole profile. Bundle result: "ok" boolean, "verdict" String
     * (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     * "detail" String optional.
     */
    Bundle applyProfile(String profileId);

    /**
     * Shows EVProfile's profile picker overlay and leaves the choice to the driver.
     * Same result keys as applyProfile. Refused while the car moves or when its speed is
     * unreadable — the picker exists to apply a profile — and UNSUPPORTED when EVProfile
     * holds no profile to offer.
     */
    Bundle showProfilePicker();
}
