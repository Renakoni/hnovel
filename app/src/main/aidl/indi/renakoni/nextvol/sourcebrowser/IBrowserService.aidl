package indi.renakoni.nextvol.sourcebrowser;
import indi.renakoni.nextvol.sourcebrowser.IBrowserHost;
import android.os.ParcelFileDescriptor;
interface IBrowserService {
    void start(String payload, IBrowserHost host);
    void localStorage(in ParcelFileDescriptor payload, IBrowserHost host);
    boolean cancel(String jobId);
    void shutdown();
}
