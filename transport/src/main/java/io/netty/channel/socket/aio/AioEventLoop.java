/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.aio;

import io.netty.channel.EventExecutor;
import io.netty.channel.EventLoopException;
import io.netty.channel.MultithreadEventLoop;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;

public class AioEventLoop extends MultithreadEventLoop {

    private static final ConcurrentMap<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<Class<?>, Field[]>();
    private static final Field[] FAILURE = new Field[0];

    final AsynchronousChannelGroup group;

    public AioEventLoop() {
        this(0);
    }

    public AioEventLoop(int nThreads) {
        this(nThreads, null);
    }

    public AioEventLoop(int nThreads, ThreadFactory threadFactory) {
        super(nThreads, threadFactory);
        try {
            group = AsynchronousChannelGroup.withThreadPool(this);
        } catch (IOException e) {
            throw new EventLoopException("Failed to create an AsynchronousChannelGroup", e);
        }
    }

    @Override
    public void execute(Runnable command) {
        Class<? extends Runnable> commandType = command.getClass();
        if (commandType.getName().startsWith("sun.nio.ch.")) {
            executeAioTask(command);
        } else {
            super.execute(command);
        }
    }

    private void executeAioTask(Runnable command) {
        AbstractAioChannel ch = null;
        try {
            ch = findChannel(command);
        } catch (Throwable t) {
            // Ignore
        }

        EventExecutor l;
        if (ch != null) {
            l = ch.eventLoop();
        } else {
            l = unsafe().nextChild();
        }

        if (l.isShutdown()) {
            command.run();
        } else {
            l.execute(command);
        }
    }

    private static AbstractAioChannel findChannel(Runnable command) throws Exception {
        Class<?> commandType = command.getClass();
        Field[] fields = fieldCache.get(commandType);
        if (fields == null) {
            try {
                fields = findFieldSequence(command, new ArrayDeque<Field>(2));
            } catch (Throwable t) {
                // Failed to get the field list
            }

            if (fields == null) {
                fields = FAILURE;
            }

            fieldCache.put(commandType, fields); // No need to use putIfAbsent()
        }

        if (fields == FAILURE) {
            return null;
        }

        final int lastIndex = fields.length - 1;
        for (int i = 0; i < lastIndex; i ++) {
            command = (Runnable) fields[i].get(command);
        }

        return (AbstractAioChannel) fields[lastIndex].get(command);
    }

    private static Field[] findFieldSequence(Runnable command, Deque<Field> fields) throws Exception {
        Class<?> commandType = command.getClass();
        for (Field f: commandType.getDeclaredFields()) {
            if (f.getType() == Runnable.class) {
                f.setAccessible(true);
                fields.addLast(f);
                try {
                    Field[] ret = findFieldSequence((Runnable) f.get(command), fields);
                    if (ret != null) {
                        return ret;
                    }
                } finally {
                    fields.removeLast();
                }
            }

            if (f.getType() == Object.class) {
                f.setAccessible(true);
                fields.addLast(f);
                try {
                    Object candidate = f.get(command);
                    if (candidate instanceof AbstractAioChannel) {
                        return fields.toArray(new Field[fields.size()]);
                    }
                } finally {
                    fields.removeLast();
                }
            }
        }

        return null;
    }

    @Override
    protected EventExecutor newChild(ThreadFactory threadFactory, Object... args) throws Exception {
        return new AioChildEventLoop(this, threadFactory);
    }
}
