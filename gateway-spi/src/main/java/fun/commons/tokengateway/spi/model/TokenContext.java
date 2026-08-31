package fun.commons.tokengateway.spi.model;

/**
 * 凭证上下文 (TOKEN_VALIDATE 面产物, M0 冻结契约).
 *
 * <p>SPI 铁律 4: toString 必须脱敏 —— 明文凭证只进 TokenValidator.validate 入参,
 * 本类型不持有明文, maskedCredential 仅作日志/排障展示.
 */
public record TokenContext(
        String tenantId,
        String userId,
        String tokenId,
        String groupId,
        boolean active,
        String maskedCredential) {

    @Override
    public String toString() {
        return "TokenContext{tenantId=" + tenantId + ", userId=" + userId
                + ", tokenId=" + tokenId + ", groupId=" + groupId
                + ", active=" + active + ", credential=" + maskedCredential + "}";
    }
}
