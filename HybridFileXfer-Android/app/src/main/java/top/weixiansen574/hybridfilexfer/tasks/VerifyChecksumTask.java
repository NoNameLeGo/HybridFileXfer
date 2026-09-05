package top.weixiansen574.hybridfilexfer.tasks;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;

/**
 * 传输完成后的可选 MD5 文件校验任务（Android 端由服务端发起）。
 */
public class VerifyChecksumTask extends BackstageTask<BTransferFileCallback> {
    private final HFXServer server;

    public VerifyChecksumTask(BTransferFileCallback uiHandler, HFXServer server) {
        super(uiHandler);
        this.server = server;
    }

    @Override
    protected void onStart(BTransferFileCallback callback) throws Throwable {
        server.verifyFiles(callback);
    }
}