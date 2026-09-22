package indi.renakoni.nextvol.sourceexecution;

import android.os.ParcelFileDescriptor;

oneway interface IExecutionCallback {
    void onResult(in byte[] result);
    void onResultFile(in ParcelFileDescriptor result);
}
