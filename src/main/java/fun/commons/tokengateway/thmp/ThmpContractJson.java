package fun.commons.tokengateway.thmp;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * THMP 契约面共享 ObjectMapper (线程安全单例; 未知字段容忍 — 18 号契约演进向前兼容).
 */
final class ThmpContractJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ThmpContractJson() {
    }
}
