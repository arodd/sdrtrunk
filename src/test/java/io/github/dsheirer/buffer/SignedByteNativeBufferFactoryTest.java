package io.github.dsheirer.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class SignedByteNativeBufferFactoryTest
{
    @Test
    void usesActualTransferLengthWhenShorterThanBuffer() throws Exception
    {
        SignedByteNativeBufferFactory factory = new SignedByteNativeBufferFactory();

        int capacity = 8192;
        int actualLength = SignedByteNativeBuffer.BYTES_PER_FRAGMENT; //4,096 bytes
        byte validValue = 0x11;
        byte staleValue = 0x66;

        ByteBuffer buffer = ByteBuffer.allocate(capacity);

        for(int i = 0; i < actualLength; i++)
        {
            buffer.put(validValue);
        }

        for(int i = actualLength; i < capacity; i++)
        {
            buffer.put(staleValue);
        }

        buffer.position(0);
        buffer.limit(actualLength);

        SignedByteNativeBuffer nativeBuffer = (SignedByteNativeBuffer)factory.getBuffer(buffer.slice(), 123L);

        assertNotNull(nativeBuffer);
        assertEquals(actualLength / 2, nativeBuffer.sampleCount());

        Field samplesField = SignedByteNativeBuffer.class.getDeclaredField("mSamples");
        samplesField.setAccessible(true);
        byte[] samples = (byte[])samplesField.get(nativeBuffer);

        assertEquals(actualLength, samples.length);

        for(byte sample: samples)
        {
            assertEquals(validValue, sample);
        }
    }
}
