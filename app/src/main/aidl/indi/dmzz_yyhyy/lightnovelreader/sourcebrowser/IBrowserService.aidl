package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser;
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.IBrowserHost;
import android.os.ParcelFileDescriptor;
interface IBrowserService {
    void start(String payload, IBrowserHost host);
    void localStorage(in ParcelFileDescriptor payload, IBrowserHost host);
    void shutdown();
}
