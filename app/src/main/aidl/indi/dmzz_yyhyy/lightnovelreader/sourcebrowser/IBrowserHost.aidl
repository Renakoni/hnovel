package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser;
import android.os.ParcelFileDescriptor;
interface IBrowserHost {
    ParcelFileDescriptor call(String operation, String arguments);
    void complete(in ParcelFileDescriptor result);
}
