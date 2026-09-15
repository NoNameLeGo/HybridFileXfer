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
        //已有校验在进行中时不再重复发起：必须把异常抛出去，否则对话框会一直停在「校验中…」
        if (!server.verifyFiles(callback)) {
            throw new IllegalStateException("已有一个校验在进行中，请等它结束");
        }
    }
}