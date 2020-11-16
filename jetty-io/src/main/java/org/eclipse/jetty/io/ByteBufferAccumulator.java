//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Accumulates data into a list of ByteBuffers which can then be combined into a single buffer or written to an OutputStream.
 * The buffer list automatically grows as data is written to it, the buffers are taken from the
 * supplied {@link ByteBufferPool} or freshly allocated if one is not supplied.
 *
 * The method {@link #ensureBuffer(int, int)} is used to write directly to the last buffer stored in the buffer list,
 * if there is less than a certain amount of space available in that buffer then a new one will be allocated and returned instead.
 * @see #ensureBuffer(int, int)
 */
public class ByteBufferAccumulator implements AutoCloseable
{
    private final List<ByteBuffer> _buffers = new ArrayList<>();
    private final ByteBufferPool _bufferPool;

    public ByteBufferAccumulator()
    {
        this(null);
    }

    public ByteBufferAccumulator(ByteBufferPool bufferPool)
    {
        _bufferPool = (bufferPool == null) ? new NullByteBufferPool() : bufferPool;
    }

    public int getLength()
    {
        int length = 0;
        for (ByteBuffer buffer : _buffers)
            length += buffer.remaining();
        return length;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return _bufferPool;
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     */
    public ByteBuffer ensureBuffer(int minAllocationSize)
    {
        return ensureBuffer(1, minAllocationSize);
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minSize the smallest amount of remaining space before a new buffer is allocated.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     */
    public ByteBuffer ensureBuffer(int minSize, int minAllocationSize)
    {
        ByteBuffer buffer = _buffers.isEmpty() ? BufferUtil.EMPTY_BUFFER : _buffers.get(_buffers.size() - 1);
        if (BufferUtil.space(buffer) < minSize)
        {
            buffer = _bufferPool.acquire(minAllocationSize, false);
            _buffers.add(buffer);
        }

        return buffer;
    }

    public void copyBytes(byte[] buf, int offset, int length)
    {
        copyBuffer(BufferUtil.toBuffer(buf, offset, length));
    }

    public void copyBuffer(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            ByteBuffer b = ensureBuffer(buffer.remaining());
            int pos = BufferUtil.flipToFill(b);
            BufferUtil.put(buffer, b);
            BufferUtil.flipToFlush(b, pos);
        }
    }

    /**
     * Take the combined buffer containing all content written to the accumulator.
     * The caller is responsible for releasing this {@link ByteBuffer} back into the {@link ByteBufferPool}.
     * @return a buffer containing all content written to the accumulator.
     * @see #toByteBuffer()
     */
    public ByteBuffer takeByteBuffer()
    {
        ByteBuffer combinedBuffer;
        if (_buffers.size() == 1)
        {
            combinedBuffer = _buffers.get(0);
            _buffers.clear();
            return combinedBuffer;
        }

        int length = getLength();
        combinedBuffer = _bufferPool.acquire(length, false);
        BufferUtil.clearToFill(combinedBuffer);
        for (ByteBuffer buffer : _buffers)
        {
            combinedBuffer.put(buffer);
            _bufferPool.release(buffer);
        }
        BufferUtil.flipToFlush(combinedBuffer, 0);
        _buffers.clear();
        return combinedBuffer;
    }

    /**
     * Take the combined buffer containing all content written to the accumulator.
     * The returned buffer is still contained within the accumulator and will be released back to the {@link ByteBufferPool}
     * when the accumulator is closed.
     * @return a buffer containing all content written to the accumulator.
     * @see #takeByteBuffer()
     * @see #close()
     */
    public ByteBuffer toByteBuffer()
    {
        ByteBuffer combinedBuffer = takeByteBuffer();
        _buffers.add(combinedBuffer);
        return combinedBuffer;
    }

    /**
     * @return a newly allocated byte array containing all content written into the accumulator.
     */
    public byte[] toByteArray()
    {
        int length = getLength();
        if (length == 0)
            return new byte[0];

        byte[] bytes = new byte[length];
        writeTo(BufferUtil.toBuffer(bytes));
        return bytes;
    }

    public void writeTo(ByteBuffer buffer)
    {
        int pos = BufferUtil.flipToFill(buffer);
        for (ByteBuffer bb : _buffers)
        {
            buffer.put(bb);
        }
        BufferUtil.flipToFlush(buffer, pos);
    }

    public void writeTo(OutputStream out) throws IOException
    {
        for (ByteBuffer bb : _buffers)
        {
            BufferUtil.writeTo(bb, out);
        }
    }

    @Override
    public void close()
    {
        _buffers.forEach(_bufferPool::release);
        _buffers.clear();
    }
}
