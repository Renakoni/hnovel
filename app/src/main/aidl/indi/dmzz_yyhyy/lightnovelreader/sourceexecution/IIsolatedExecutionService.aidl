package indi.dmzz_yyhyy.lightnovelreader.sourceexecution;

import indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IExecutionCallback;

interface IIsolatedExecutionService {
    void execute(in byte[] request, IExecutionCallback callback);
    oneway void terminate();
    int workerUid();
}
