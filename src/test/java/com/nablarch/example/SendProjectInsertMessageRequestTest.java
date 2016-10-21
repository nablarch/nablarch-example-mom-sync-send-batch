package com.nablarch.example;

import org.junit.Test;

import com.nablarch.example.test.BatchRequestTestBase;

/**
 */
public class SendProjectInsertMessageRequestTest extends BatchRequestTestBase {

    /** 正常終了のテストケース。 */
    @Test
    public void testNormalEnd() {
        execute();
    }

    /** 異常終了のテストケース。 */
    @Test
    public void testAbnormalEnd() {
        execute();
    }
}
