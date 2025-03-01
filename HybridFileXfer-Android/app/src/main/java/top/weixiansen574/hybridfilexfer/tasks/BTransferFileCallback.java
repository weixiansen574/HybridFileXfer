package top.weixiansen574.hybridfilexfer.tasks;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

public interface BTransferFileCallback extends BackstageTask.BaseEventHandler, TransferFileCallback {
}
