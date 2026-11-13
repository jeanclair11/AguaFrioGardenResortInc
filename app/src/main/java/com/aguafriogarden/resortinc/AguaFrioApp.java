package com.aguafriogarden.resortinc;

import android.app.Application;

/** Process start-up: loads (and refreshes) the backend's status catalog before any screen asks. */
public class AguaFrioApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        StatusCatalog.init(this);
        ReservationFeed.init(this);
        NotificationStore.init(this);
        // Also when the process was started by a push while the app was closed.
        PushTokens.ensureInitialized(this);
    }
}
