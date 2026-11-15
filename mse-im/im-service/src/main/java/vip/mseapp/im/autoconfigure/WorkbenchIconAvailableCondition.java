package vip.mseapp.im.autoconfigure;

/**
 * 工作台图标存储的 mse-fs 可用性条件（判据见 {@link ImFsStorageCondition}，
 * 开关属性 {@code mse.im.workbench.icon-storage}）。
 */
public class WorkbenchIconAvailableCondition extends ImFsStorageCondition {

    public WorkbenchIconAvailableCondition() {
        super("mse.im.workbench.icon-storage");
    }
}
