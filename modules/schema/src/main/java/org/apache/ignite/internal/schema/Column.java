/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.schema;

import java.io.Serializable;
import java.util.function.Supplier;
import org.apache.ignite.internal.tostring.IgniteToStringExclude;
import org.apache.ignite.internal.tostring.S;
import org.jetbrains.annotations.NotNull;

/**
 * Column descriptor which contains a column name, a type and a nullability flag.
 * <p>
 * Because of columns must be written to a row in a specific order,
 * column write order ({@link #schemaIndex}) may differ from the user-defined order ({@link #columnOrder}).
 */
public class Column implements Serializable {
    /** Absolute index in schema descriptor. */
    private final int schemaIndex;

    /** User column order as defined in table definition. */
    private final int columnOrder;

    /**
     * Column name.
     */
    private final String name;

    /**
     * An instance of column data type.
     */
    private final NativeType type;

    /**
     * If {@code false}, null values will not be allowed for this column.
     */
    private final boolean nullable;

    /**
     * Default value supplier.
     */
    @IgniteToStringExclude
    private final Supplier<Object> defValSup;

    /**
     * @param name Column name.
     * @param type An instance of column data type.
     * @param nullable If {@code false}, null values will not be allowed for this column.
     */
    public Column(
        String name,
        NativeType type,
        boolean nullable
    ) {
        this(-1, -1, name, type, nullable, (Supplier<Object> & Serializable)() -> null);
    }

    /**
     * @param name Column name.
     * @param type An instance of column data type.
     * @param nullable If {@code false}, null values will not be allowed for this column.
     * @param defValSup Default value supplier.
     */
    public Column(
        String name,
        NativeType type,
        boolean nullable,
        @NotNull Supplier<Object> defValSup
    ) {
        this(-1, -1, name, type, nullable, defValSup);
    }

    /**
     * @param columnOrder Column order in table definition.
     * @param name Column name.
     * @param type An instance of column data type.
     * @param nullable If {@code false}, null values will not be allowed for this column.
     * @param defValSup Default value supplier.
     */
    public Column(
        int columnOrder,
        String name,
        NativeType type,
        boolean nullable,
        @NotNull Supplier<Object> defValSup
    ) {
        this(-1, columnOrder, name, type, nullable, defValSup);
    }

    /**
     * @param schemaIndex Absolute index of this column in its schema descriptor.
     * @param columnOrder Column order defined in table definition.
     * @param name Column name.
     * @param type An instance of column data type.
     * @param nullable If {@code false}, null values will not be allowed for this column.
     * @param defValSup Default value supplier.
     */
    private Column(
        int schemaIndex,
        int columnOrder,
        String name,
        NativeType type,
        boolean nullable,
        @NotNull Supplier<Object> defValSup
    ) {
        this.schemaIndex = schemaIndex;
        this.columnOrder = columnOrder;
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defValSup = defValSup;
    }

    /**
     * @return Absolute index of this column in its schema descriptor.
     */
    public int schemaIndex() {
        return schemaIndex;
    }

    /**
     * @return User column order as defined in table definition.
     */
    public int columnOrder() {
        return columnOrder;
    }

    /**
     * @return Column name.
     */
    public String name() {
        return name;
    }

    /**
     * @return An instance of column data type.
     */
    public NativeType type() {
        return type;
    }

    /**
     * @return {@code false} if null values will not be allowed for this column.
     */
    public boolean nullable() {
        return nullable;
    }

    /**
     * Get default value for the column.
     *
     * @return Default value.
     */
    public Object defaultValue() {
        return defValSup.get();
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        Column col = (Column)o;

        return name.equals(col.name) &&
            type.equals(col.type);
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return name.hashCode() + 31 * type.hashCode();
    }

    /**
     * Validate the object by column's constraint.
     *
     * @param val Object to validate.
     */
    public void validate(Object val) {
        if (val == null && !nullable) {
            throw new IllegalArgumentException("Failed to set column (null was passed, but column is not nullable): " +
                "[col=" + this + ']');
        }

        NativeType objType = NativeTypes.fromObject(val);

        if (objType != null && type.mismatch(objType)) {
            throw new InvalidTypeException("Column's type mismatch [" +
                "column=" + this +
                ", expectedType=" + type +
                ", actualType=" + objType +
                ", val=" + val + ']');
        }
    }

    /**
     * Copy column with new schema index.
     *
     * @param schemaIndex Column index in the schema.
     * @return Column.
     */
    public Column copy(int schemaIndex) {
        return new Column(schemaIndex, columnOrder, name, type, nullable, defValSup);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(Column.class, this);
    }
}
