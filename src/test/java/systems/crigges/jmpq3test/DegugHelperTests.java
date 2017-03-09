package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.DebugHelper;

/**
 * Created by Frotty on 09.03.2017.
 */
public class DegugHelperTests {

    @Test
    public void testDebugHelper() {
        Assert.assertTrue(DebugHelper.bytesToHex(new byte[]{0}).equalsIgnoreCase("00"));
        Assert.assertTrue(DebugHelper.bytesToHex(new byte[]{1}).equalsIgnoreCase("01"));
        Assert.assertTrue(DebugHelper.bytesToHex(new byte[]{10}).equalsIgnoreCase("0A"));
        Assert.assertTrue(DebugHelper.bytesToHex(new byte[]{16}).equalsIgnoreCase("10"));
    }
}
