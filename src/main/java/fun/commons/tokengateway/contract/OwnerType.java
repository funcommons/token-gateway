package fun.commons.tokengateway.contract;

/**
 * 渠道归属类型 (从 backend/common 复制, 自包含).
 */
public enum OwnerType {

    PLATFORM,

    TENANT;

    public boolean isPlatform() {
        return this == PLATFORM;
    }

    public boolean isTenant() {
        return this == TENANT;
    }
}
