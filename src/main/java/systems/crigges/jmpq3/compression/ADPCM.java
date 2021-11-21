package systems.crigges.jmpq3.compression;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ADPCM {
    private static final byte INITIAL_ADPCM_STEP_INDEX = 0x2C;

    private static final byte[] CHANGE_TABLE =
        {
            -1, 0, -1, 4, -1, 2, -1, 6,
            -1, 1, -1, 5, -1, 3, -1, 7,
            -1, 1, -1, 5, -1, 3, -1, 7,
            -1, 2, -1, 4, -1, 6, -1, 8
        };

    private static final short[] STEP_TABLE =
        {
            7, 8, 9, 10, 11, 12, 13, 14,
            16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
            7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767
        };

    private static class Channel {
        public short sampleValue;
        public byte stepIndex;
    }

    private final Channel[] state;

    public ADPCM(int channelmax) {
        state = new Channel[channelmax];
        for (int i = 0; i < state.length; i += 1) state[i] = new Channel();
    }

    public void decompress(ByteBuffer in, ByteBuffer out, int channeln) {
        // prepare buffers
        in.order(ByteOrder.LITTLE_ENDIAN);
        out.order(ByteOrder.LITTLE_ENDIAN);

        byte stepshift = (byte) (in.getShort() >>> 8);

        // initialize channels
        for (int i = 0; i < channeln; i += 1) {
            Channel chan = state[i];
            chan.stepIndex = INITIAL_ADPCM_STEP_INDEX;
            chan.sampleValue = in.getShort();
            out.putShort(chan.sampleValue);
        }

        int current = 0;

        // decompress
        while (in.hasRemaining()) {
            final byte op = in.get();
            final Channel chan = state[current];

            if ((op & 0x80) != 0) {
                switch (op & 0x7F) {
                    // write current value
                    case 0:
                        if (chan.stepIndex != 0)
                            chan.stepIndex -= 1;
                        out.putShort(chan.sampleValue);
                        current = (current + 1) % channeln;
                        break;
                    // increment period
                    case 1:
                        chan.stepIndex += 8;
                        if (chan.stepIndex >= STEP_TABLE.length)
                            chan.stepIndex = (byte) (STEP_TABLE.length - 1);
                        break;
                    // skip channel (unused?)
                    case 2:
                        current = (current + 1) % channeln;
                        break;

                    // all other values (unused?)
                    default:
                        chan.stepIndex -= 8;
                        if (chan.stepIndex < 0)
                            chan.stepIndex = 0;
                        break;
                }
            } else {
                // adjust value
                short stepbase = STEP_TABLE[chan.stepIndex];
                short step = (short) (stepbase >>> stepshift);
                for (int i = 0; i < 6; i += 1) {
                    if (((op & 0xff) & 1 << i) != 0)
                        step += stepbase >> i;
                }

                if ((op & 0x40) != 0) {
                    chan.sampleValue = (short) Math.max((int) chan.sampleValue - step, Short.MIN_VALUE);
                } else {
                    chan.sampleValue = (short) Math.min((int) chan.sampleValue + step, Short.MAX_VALUE);
                }

                out.putShort(chan.sampleValue);

                chan.stepIndex += CHANGE_TABLE[op & 0x1F];
                if (chan.stepIndex < 0)
                    chan.stepIndex = 0;
                else if (chan.stepIndex >= STEP_TABLE.length)
                    chan.stepIndex = (byte) (STEP_TABLE.length - 1);

                current = (current + 1) % channeln;
            }
        }
    }
}
