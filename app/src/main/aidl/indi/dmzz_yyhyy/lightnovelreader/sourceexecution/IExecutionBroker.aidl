package indi.dmzz_yyhyy.lightnovelreader.sourceexecution;

// The host authenticates the calling worker UID; neither argument can select a source/session.
interface IExecutionBroker {
    byte[] call(String operation, in byte[] arguments);
}
