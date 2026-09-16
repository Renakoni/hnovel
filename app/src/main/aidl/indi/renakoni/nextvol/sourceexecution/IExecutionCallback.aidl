package indi.renakoni.nextvol.sourceexecution;

oneway interface IExecutionCallback {
    void onResult(in byte[] result);
}
