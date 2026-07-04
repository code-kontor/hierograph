package org.hg.fixture.basic.rel.source;

import org.hg.fixture.basic.rel.target.ValueHolder;

public class FieldAccessor {
    public int readAndWrite(ValueHolder holder) {
        holder.value = 42;
        return holder.value;
    }
}
