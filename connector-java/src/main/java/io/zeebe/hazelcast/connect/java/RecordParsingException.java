package io.zeebe.hazelcast.connect.java;

import com.google.protobuf.InvalidProtocolBufferException;

public class RecordParsingException extends InvalidProtocolBufferException {
    private final int readCount;

    public RecordParsingException(int readCount, InvalidProtocolBufferException e) {
        super(e);
        this.readCount = readCount;
    }

    public int getReadCount() {
        return readCount;
    }
}
