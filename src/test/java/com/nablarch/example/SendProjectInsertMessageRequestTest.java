package com.nablarch.example;

import nablarch.test.core.batch.BatchRequestTestSupport;
import nablarch.test.junit5.extension.batch.BatchRequestTest;
import org.junit.jupiter.api.Test;

/**
 */
@BatchRequestTest
class SendProjectInsertMessageRequestTest {
    BatchRequestTestSupport support;

    /** 正常終了のテストケース。 */
    @Test
    void testNormalEnd() {
        support.execute(support.testName.getMethodName());
    }

    /** 異常終了のテストケース。 */
    @Test
    void testAbnormalEnd() {
        support.execute(support.testName.getMethodName());
    }
}
