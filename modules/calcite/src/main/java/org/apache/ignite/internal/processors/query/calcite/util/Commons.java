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

package org.apache.ignite.internal.processors.query.calcite.util;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.config.Lex;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;
import org.apache.ignite.internal.generated.query.calcite.sql.IgniteSqlParserImpl;
import org.apache.ignite.internal.processors.query.calcite.SqlCursor;
import org.apache.ignite.internal.processors.query.calcite.SqlQueryType;
import org.apache.ignite.internal.processors.query.calcite.exec.exp.ExpressionFactoryImpl;
import org.apache.ignite.internal.processors.query.calcite.metadata.cost.IgniteCostFactory;
import org.apache.ignite.internal.processors.query.calcite.prepare.AbstractMultiStepPlan;
import org.apache.ignite.internal.processors.query.calcite.prepare.ExplainPlan;
import org.apache.ignite.internal.processors.query.calcite.prepare.FieldsMetadata;
import org.apache.ignite.internal.processors.query.calcite.prepare.MultiStepPlan;
import org.apache.ignite.internal.processors.query.calcite.prepare.PlanningContext;
import org.apache.ignite.internal.processors.query.calcite.prepare.QueryPlan;
import org.apache.ignite.internal.processors.query.calcite.sql.IgniteSqlConformance;
import org.apache.ignite.internal.processors.query.calcite.sql.fun.IgniteSqlOperatorTable;
import org.apache.ignite.internal.processors.query.calcite.type.IgniteTypeFactory;
import org.apache.ignite.internal.processors.query.calcite.type.IgniteTypeSystem;
import org.apache.ignite.internal.schema.NativeType;
import org.apache.ignite.internal.util.ArrayUtils;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.lang.IgniteLogger;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.codehaus.commons.compiler.ICompilerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.calcite.rex.RexUtil.EXECUTOR;
import static org.apache.ignite.internal.util.CollectionUtils.nullOrEmpty;

/**
 * Utility methods.
 */
public final class Commons {
    /** */
    public static final int IN_BUFFER_SIZE = 512;

    /** */
    public static final FrameworkConfig FRAMEWORK_CONFIG = Frameworks.newConfigBuilder()
        .executor(EXECUTOR)
        .sqlToRelConverterConfig(SqlToRelConverter.config()
            .withTrimUnusedFields(true)
            // currently SqlToRelConverter creates not optimal plan for both optimization and execution
            // so it's better to disable such rewriting right now
            // TODO: remove this after IGNITE-14277
            .withInSubQueryThreshold(Integer.MAX_VALUE)
            .withDecorrelationEnabled(true)
            .withHintStrategyTable(
                HintStrategyTable.builder()
                    .hintStrategy("DISABLE_RULE", (hint, rel) -> true)
                    .hintStrategy("EXPAND_DISTINCT_AGG", (hint, rel) -> rel instanceof Aggregate)
                    .build()
            )
        )
        .parserConfig(
            SqlParser.config()
                .withParserFactory(IgniteSqlParserImpl.FACTORY)
                .withLex(Lex.ORACLE)
                .withConformance(IgniteSqlConformance.INSTANCE))
        .sqlValidatorConfig(SqlValidator.Config.DEFAULT
            .withIdentifierExpansion(true)
            .withSqlConformance(IgniteSqlConformance.INSTANCE))
        // Dialects support.
        .operatorTable(SqlOperatorTables.chain(
            SqlLibraryOperatorTableFactory.INSTANCE
                .getOperatorTable(
                    SqlLibrary.STANDARD,
                    SqlLibrary.POSTGRESQL,
                    SqlLibrary.ORACLE,
                    SqlLibrary.MYSQL),
            IgniteSqlOperatorTable.instance()))
        // Context provides a way to store data within the planner session that can be accessed in planner rules.
        .context(Contexts.empty())
        // Custom cost factory to use during optimization
        .costFactory(new IgniteCostFactory())
        .typeSystem(IgniteTypeSystem.INSTANCE)
        .build();

    /** */
    private Commons(){}

    public static <T> SqlCursor<T> createCursor(Iterable<T> iterable, QueryPlan plan) {
        return createCursor(iterable.iterator(), plan);
    }

    public static <T> SqlCursor<T> createCursor(Iterator<T> iter, QueryPlan plan) {
        return new SqlCursor<>() {
            @Override public SqlQueryType getQueryType() {
                return SqlQueryType.mapPlanTypeToSqlType(plan.type());
            }

            @Override public FieldsMetadata getColumnMetadata() {
                return plan instanceof AbstractMultiStepPlan ? ((MultiStepPlan)plan).fieldsMetadata()
                    : ((ExplainPlan)plan).fieldsMeta();
            }

            @Override public void remove() {
                iter.remove();
            }

            @Override public boolean hasNext() {
                return iter.hasNext();
            }

            @Override public T next() {
                return iter.next();
            }

            @NotNull @Override public Iterator<T> iterator() {
                return iter;
            }

            @Override public void close() throws Exception {
                if (iter instanceof AutoCloseable)
                    ((AutoCloseable)iter).close();
            }
        };
    }

    /**
     * Combines two lists.
     */
    public static <T> List<T> combine(List<T> left, List<T> right) {
        Set<T> set = new HashSet<>(left.size() + right.size());

        set.addAll(left);
        set.addAll(right);

        return new ArrayList<>(set);
    }

    /**
     * Intersects two lists.
     */
    public static <T> List<T> intersect(List<T> left, List<T> right) {
        if (nullOrEmpty(left) || nullOrEmpty(right))
            return Collections.emptyList();

        return left.size() > right.size()
            ? intersect(new HashSet<>(right), left)
            : intersect(new HashSet<>(left), right);
    }

    /**
     * Intersects a set and a list.
     *
     * @return A List of unique entries that presented in both the given set and the given list.
     */
    public static <T> List<T> intersect(Set<T> set, List<T> list) {
        if (nullOrEmpty(set) || nullOrEmpty(list))
            return Collections.emptyList();

        return list.stream()
            .filter(set::contains)
            .collect(Collectors.toList());
    }

    /**
     * Returns a given list as a typed list.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<T> cast(List<?> src) {
        return (List)src;
    }

    /**
     * Transforms a given list using map function.
     */
    public static <T, R> List<R> transform(@NotNull List<T> src, @NotNull Function<T, R> mapFun) {
        if (nullOrEmpty(src))
            return Collections.emptyList();

        List<R> list = new ArrayList<>(src.size());

        for (T t : src)
            list.add(mapFun.apply(t));

        return list;
    }

    /**
     * Extracts type factory.
     */
    public static IgniteTypeFactory typeFactory(RelNode rel) {
        return typeFactory(rel.getCluster());
    }

    /**
     * Extracts type factory.
     */
    public static IgniteTypeFactory typeFactory(RelOptCluster cluster) {
        return (IgniteTypeFactory)cluster.getTypeFactory();
    }

    /**
     * Extracts planner context.
     */
    public static PlanningContext context(RelNode rel) {
        return context(rel.getCluster());
    }

    /**
     * Extracts planner context.
     */
    public static PlanningContext context(RelOptCluster cluster) {
        return context(cluster.getPlanner().getContext());
    }

    /**
     * Extracts planner context.
     */
    public static PlanningContext context(Context ctx) {
        return Objects.requireNonNull(ctx.unwrap(PlanningContext.class));
    }

    /**
     * @param params Parameters.
     * @return Parameters map.
     */
    public static Map<String, Object> parametersMap(@Nullable Object[] params) {
        HashMap<String, Object> res = new HashMap<>();

        return params != null ? populateParameters(res, params) : res;
    }

    /**
     * Populates a provided map with given parameters.
     *
     * @param dst Map to populate.
     * @param params Parameters.
     * @return Parameters map.
     */
    public static Map<String, Object> populateParameters(@NotNull Map<String, Object> dst, @Nullable Object[] params) {
        if (!ArrayUtils.nullOrEmpty(params)) {
            for (int i = 0; i < params.length; i++)
                dst.put("?" + i, params[i]);
        }
        return dst;
    }

    /**
     * @param o Object to close.
     */
    public static void close(Object o) throws Exception {
        if (o instanceof AutoCloseable)
            ((AutoCloseable) o).close();
    }

    /**
     * Closes given resource logging possible checked exception.
     *
     * @param o Resource to close. If it's {@code null} - it's no-op.
     * @param log Logger to log possible checked exception.
     */
    public static void close(Object o, @NotNull IgniteLogger log) {
        if (o instanceof AutoCloseable) {
            try {
                ((AutoCloseable)o).close();
            }
            catch (Exception e) {
                log.warn("Failed to close resource: " + e.getMessage(), e);
            }
        }
    }
//
//    /**
//     * @param o Object to close.
//     */
//    public static void closeQuiet(Object o) {
//        if (o instanceof AutoCloseable)
//            U.closeQuiet((AutoCloseable) o);
//    }

    /** */
    public static <T> List<T> flat(List<List<? extends T>> src) {
        return src.stream().flatMap(List::stream).collect(Collectors.toList());
    }

    /** */
    public static int max(ImmutableIntList list) {
        if (list.isEmpty())
            throw new UnsupportedOperationException();

        int res = list.getInt(0);

        for (int i = 1; i < list.size(); i++)
            res = Math.max(res, list.getInt(i));

        return res;
    }

    /** */
    public static int min(ImmutableIntList list) {
        if (list.isEmpty())
            throw new UnsupportedOperationException();

        int res = list.getInt(0);

        for (int i = 1; i < list.size(); i++)
            res = Math.min(res, list.getInt(i));

        return res;
    }

    /** */
    public static <T> T compile(Class<T> interfaceType, String body) {
        final boolean debug = CalciteSystemProperty.DEBUG.value();

        if (debug)
            Util.debugCode(System.out, body);

        try {
            final ICompilerFactory compilerFactory;

            try {
                compilerFactory = CompilerFactoryFactory.getDefaultCompilerFactory();
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Unable to instantiate java compiler", e);
            }

            IClassBodyEvaluator cbe = compilerFactory.newClassBodyEvaluator();

            cbe.setImplementedInterfaces(new Class[]{ interfaceType });
            cbe.setParentClassLoader(ExpressionFactoryImpl.class.getClassLoader());

            if (debug)
                // Add line numbers to the generated janino class
                cbe.setDebuggingInformation(true, true, true);

            return (T) cbe.createInstance(new StringReader(body));
        } catch (Exception e) {
            throw new IgniteException(e);
        }
    }

    /** */
    public static void checkRange(@NotNull Object[] array, int idx) {
        if (idx < 0 || idx >= array.length)
            throw new ArrayIndexOutOfBoundsException(idx);
    }

    /** */
    public static <T> T[] ensureCapacity(T[] array, int required) {
        if (required < 0)
            throw new IllegalArgumentException("Capacity must not be negative");

        return array.length <= required ? Arrays.copyOf(array, nextPowerOf2(required)) : array;
    }

    /**
     * Round up the argument to the next highest power of 2;
     *
     * @param v Value to round up.
     * @return Next closest power of 2.
     */
    public static int nextPowerOf2(int v) {
        if (v < 0)
            throw new IllegalArgumentException("v must not be negative");

        if (v == 0)
            return 1;

        return 1 << (32 - Integer.numberOfLeadingZeros(v - 1));
    }

    /** */
    public static <T> Predicate<T> negate(Predicate<T> p) {
        return p.negate();
    }

    /** */
    public static Mappings.TargetMapping mapping(ImmutableBitSet bitSet, int sourceSize) {
        Mapping mapping = Mappings.create(MappingType.PARTIAL_FUNCTION, sourceSize, bitSet.cardinality());
        for (Ord<Integer> ord : Ord.zip(bitSet))
            mapping.set(ord.e, ord.i);
        return mapping;
    }

    /** */
    public static Mappings.TargetMapping inverseMapping(ImmutableBitSet bitSet, int sourceSize) {
        Mapping mapping = Mappings.create(MappingType.INVERSE_FUNCTION, sourceSize, bitSet.cardinality());
        for (Ord<Integer> ord : Ord.zip(bitSet))
            mapping.set(ord.e, ord.i);
        return mapping;
    }

    /**
     * Checks if there is a such permutation of all {@code elems} that is prefix of
     * provided {@code seq}.
     *
     * @param seq Sequence.
     * @param elems Elems.
     * @return {@code true} if there is a permutation of all {@code elems} that is prefix of {@code seq}.
     */
    public static <T> boolean isPrefix(List<T> seq, Collection<T> elems) {
        Set<T> elems0 = new HashSet<>(elems);

        if (seq.size() < elems0.size())
            return false;

        for (T e : seq) {
            if (!elems0.remove(e))
                return false;

            if (elems0.isEmpty())
                break;
        }

        return true;
    }

    /**
     * Returns the longest possible prefix of {@code seq} that could be form from provided {@code elems}.
     *
     * @param seq Sequence.
     * @param elems Elems.
     * @return The longest possible prefix of {@code seq}.
     */
    public static <T> List<T> maxPrefix(List<T> seq, Collection<T> elems) {
        List<T> res = new ArrayList<>();

        Set<T> elems0 = new HashSet<>(elems);

        for (T e : seq) {
            if (!elems0.remove(e))
                break;

            res.add(e);
        }

        return res;
    }

    /**
     * Quietly closes given object ignoring possible checked exception.
     *
     * @param obj Object to close. If it's {@code null} - it's no-op.
     */
    public static void closeQuiet(@Nullable Object obj) {
        if (obj instanceof AutoCloseable) {
            try {
                ((AutoCloseable)obj).close();
            }
            catch (Exception ignored) {
                // No-op.
            }
        }
    }

    public static Class<?> nativeTypeToClass(NativeType type) {
        assert type != null;

        switch (type.spec()) {
            case INT8:
                return Byte.class;

            case INT16:
                return Short.class;

            case INT32:
                return Integer.class;

            case INT64:
                return Long.class;

            case FLOAT:
                return Float.class;

            case DOUBLE:
                return Double.class;

            case DECIMAL:
                return BigDecimal.class;

            case UUID:
                return UUID.class;

            case STRING:
                return String.class;

            case BYTES:
                return byte[].class;

            case BITMASK:
                return BitSet.class;

            default:
                throw new IllegalArgumentException("Unsupported type " + type.spec());
        }
    }

    /** */
    public static <T> Comparator<T> compoundComparator(Iterable<Comparator<T>> cmps) {
        return (r1, r2) -> {
            for (Comparator<T> cmp : cmps) {
                int result = cmp.compare(r1, r2);

                if (result != 0) {
                    return result;
                }
            }

            return 0;
        };
    }
}
