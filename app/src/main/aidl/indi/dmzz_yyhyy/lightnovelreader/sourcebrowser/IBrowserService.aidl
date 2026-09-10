package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser;
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.IBrowserHost;
interface IBrowserService {
    void start(String payload, IBrowserHost host);
    void shutdown();
}
