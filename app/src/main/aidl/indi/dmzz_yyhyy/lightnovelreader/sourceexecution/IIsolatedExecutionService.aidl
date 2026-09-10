package indi.dmzz_yyhyy.lightnovelreader.sourceexecution;

import indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IExecutionCallback;
import indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IExecutionBroker;

interface IIsolatedExecutionService {
    void execute(in byte[] request, IExecutionCallback callback, IExecutionBroker broker);
    oneway void terminate();
    int workerUid();
}
