package indi.renakoni.nextvol.sourceexecution;

interface IForeignExecutionProbe {
    boolean isRejected(IBinder executionService);
}
