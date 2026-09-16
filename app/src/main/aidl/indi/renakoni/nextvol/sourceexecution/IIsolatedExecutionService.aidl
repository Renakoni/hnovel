package indi.renakoni.nextvol.sourceexecution;

import indi.renakoni.nextvol.sourceexecution.IExecutionCallback;
import indi.renakoni.nextvol.sourceexecution.IExecutionBroker;

interface IIsolatedExecutionService {
    void execute(in byte[] request, IExecutionCallback callback, IExecutionBroker broker);
    oneway void terminate();
    int workerUid();
}
