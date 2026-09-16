package indi.renakoni.nextvol.sourceexecution;

import android.os.ParcelFileDescriptor;

// The host authenticates the calling worker UID; neither argument can select a source/session.
interface IExecutionBroker {
    ParcelFileDescriptor call(String operation, in byte[] arguments);
}
