package vip.mseapp.im.autoconfigure;

/**
 * IM 用户头像存储的 mse-fs 可用性条件（判据见 {@link ImFsStorageCondition}，
 * 开关属性 {@code mse.im.avatar.storage}）。
 */
public class ImAvatarAvailableCondition extends ImFsStorageCondition {

    public ImAvatarAvailableCondition() {
        super("mse.im.avatar.storage");
    }
}
