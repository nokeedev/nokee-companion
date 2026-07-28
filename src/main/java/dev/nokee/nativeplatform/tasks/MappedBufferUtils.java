package dev.nokee.nativeplatform.tasks;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;

public final class MappedBufferUtils {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void unmap(MappedByteBuffer buffer) {
        if (buffer != null) {
            UNSAFE.invokeCleaner(buffer);
        }
    }
}
