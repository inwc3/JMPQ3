/*
 * 
 */
package systems.crigges.jmpq3;

// TODO: Auto-generated Javadoc
/**
 * The Class DebugHelper.
 */
public class DebugHelper {

	/** The Constant hexArray. */
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

	/**
	 * Bytes to hex.
	 *
	 * @param bytes the bytes
	 * @return the string
	 */
	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 3];
		for (int j = 0; j < Math.min(bytes.length, 500); j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 3] = hexArray[v >>> 4];
			hexChars[j * 3 + 1] = hexArray[v & 0x0F];
			hexChars[j * 3 + 2] = ' ';
		}
		return new String(hexChars);
	}

}
