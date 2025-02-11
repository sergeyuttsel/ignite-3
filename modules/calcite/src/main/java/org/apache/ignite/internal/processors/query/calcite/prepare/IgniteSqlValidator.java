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

package org.apache.ignite.internal.processors.query.calcite.prepare;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.apache.calcite.sql.validate.SqlValidatorNamespace;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.SqlValidatorTable;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.ignite.internal.processors.query.calcite.schema.IgniteTable;
import org.apache.ignite.internal.processors.query.calcite.schema.TableDescriptor;
import org.apache.ignite.internal.processors.query.calcite.type.IgniteTypeFactory;
import org.apache.ignite.internal.processors.query.calcite.util.IgniteResource;
import org.jetbrains.annotations.Nullable;

import static org.apache.calcite.util.Static.RESOURCE;
import static org.apache.ignite.internal.util.ArrayUtils.nullOrEmpty;

/** Validator. */
public class IgniteSqlValidator extends SqlValidatorImpl {
    /** Decimal of Integer.MAX_VALUE for fetch/offset bounding. */
    private static final BigDecimal DEC_INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    /** **/
    private static final int MAX_LENGTH_OF_ALIASES = 256;

    /** **/
    private static final Set<SqlKind> HUMAN_READABLE_ALIASES_FOR;

    static {
        EnumSet<SqlKind> kinds = EnumSet.noneOf(SqlKind.class);

        kinds.addAll(SqlKind.AGGREGATE);
        kinds.addAll(SqlKind.BINARY_ARITHMETIC);
        kinds.addAll(SqlKind.FUNCTION);

        kinds.add(SqlKind.CEIL);
        kinds.add(SqlKind.FLOOR);
        kinds.add(SqlKind.LITERAL);

        HUMAN_READABLE_ALIASES_FOR = Collections.unmodifiableSet(kinds);
    }

    /** Dynamic parameters. */
    Object[] parameters;

    /**
     * Creates a validator.
     *
     * @param opTab         Operator table
     * @param catalogReader Catalog reader
     * @param typeFactory   Type factory
     * @param config        Config
     * @param parameters    Dynamic parameters
     */
    public IgniteSqlValidator(SqlOperatorTable opTab, CalciteCatalogReader catalogReader,
        IgniteTypeFactory typeFactory, SqlValidator.Config config, Object[] parameters) {
        super(opTab, catalogReader, typeFactory, config);

        this.parameters = parameters;
    }

    /** {@inheritDoc} */
    @Override public void validateInsert(SqlInsert insert) {
        if (insert.getTargetColumnList() == null)
            insert.setOperand(3, inferColumnList(insert));

        super.validateInsert(insert);
    }

    /** {@inheritDoc} */
    @Override public void validateUpdate(SqlUpdate call) {
        validateUpdateFields(call);

        super.validateUpdate(call);
    }

    /** {@inheritDoc} */
    @Override public void validateLiteral(SqlLiteral literal) {
        if (literal.getTypeName() != SqlTypeName.DECIMAL)
            super.validateLiteral(literal);
    }

    /** {@inheritDoc} */
    @Override protected SqlSelect createSourceSelectForUpdate(SqlUpdate call) {
        final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
        final SqlValidatorTable table = getCatalogReader().getTable(((SqlIdentifier)call.getTargetTable()).names);

        table.unwrap(IgniteTable.class).descriptor().selectForUpdateRowType((IgniteTypeFactory)typeFactory)
            .getFieldNames().stream()
            .map(name -> new SqlIdentifier(name, SqlParserPos.ZERO))
            .forEach(selectList::add);

        int ordinal = 0;
        // Force unique aliases to avoid a duplicate for Y with SET X=Y
        for (SqlNode exp : call.getSourceExpressionList())
            selectList.add(SqlValidatorUtil.addAlias(exp, SqlUtil.deriveAliasFromOrdinal(ordinal++)));

        SqlNode sourceTable = call.getTargetTable();

        if (call.getAlias() != null) {
            sourceTable =
                SqlValidatorUtil.addAlias(
                    sourceTable,
                    call.getAlias().getSimple());
        }

        return new SqlSelect(SqlParserPos.ZERO, null, selectList, sourceTable,
            call.getCondition(), null, null, null, null, null, null, null);
    }

    /** {@inheritDoc} */
    @Override protected SqlSelect createSourceSelectForDelete(SqlDelete call) {
        final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
        final SqlValidatorTable table = getCatalogReader().getTable(((SqlIdentifier)call.getTargetTable()).names);

        table.unwrap(IgniteTable.class).descriptor().deleteRowType((IgniteTypeFactory)typeFactory)
            .getFieldNames().stream()
            .map(name -> new SqlIdentifier(name, SqlParserPos.ZERO))
            .forEach(selectList::add);

        SqlNode sourceTable = call.getTargetTable();

        if (call.getAlias() != null) {
            sourceTable =
                SqlValidatorUtil.addAlias(
                    sourceTable,
                    call.getAlias().getSimple());
        }

        return new SqlSelect(SqlParserPos.ZERO, null, selectList, sourceTable,
            call.getCondition(), null, null, null, null, null, null, null);
    }

    /** {@inheritDoc} */
    @Override protected void validateSelect(SqlSelect select, RelDataType targetRowType) {
        checkIntegerLimit(select.getFetch(), "fetch / limit");
        checkIntegerLimit(select.getOffset(), "offset");

        super.validateSelect(select, targetRowType);
    }

    /**
     * @param n Node to check limit.
     * @param nodeName Node name.
     */
    private void checkIntegerLimit(SqlNode n, String nodeName) {
        if (n instanceof SqlLiteral) {
            BigDecimal offFetchLimit = ((SqlLiteral)n).bigDecimalValue();

            if (offFetchLimit.compareTo(DEC_INT_MAX) > 0 || offFetchLimit.compareTo(BigDecimal.ZERO) < 0)
                throw newValidationError(n, IgniteResource.INSTANCE.correctIntegerLimit(nodeName));
        }
        else if (n instanceof SqlDynamicParam) {
            // will fail in params check.
            if (nullOrEmpty(parameters))
                return;

            int idx = ((SqlDynamicParam) n).getIndex();

            if (idx < parameters.length) {
                Object param = parameters[idx];
                if (parameters[idx] instanceof Integer) {
                    if ((Integer)param < 0)
                        throw newValidationError(n, IgniteResource.INSTANCE.correctIntegerLimit(nodeName));
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override public String deriveAlias(SqlNode node, int ordinal) {
        if (node.isA(HUMAN_READABLE_ALIASES_FOR)) {
            String alias = node.toSqlString(c -> c.withDialect(CalciteSqlDialect.DEFAULT)
                .withQuoteAllIdentifiers(false)
                .withAlwaysUseParentheses(false)
                .withClauseStartsLine(false)
            ).getSql();

            return alias.substring(0, Math.min(alias.length(), MAX_LENGTH_OF_ALIASES));
        }

        return super.deriveAlias(node, ordinal);
    }

    /** {@inheritDoc} */
    @Override public void validateAggregateParams(SqlCall aggCall,
        @Nullable SqlNode filter, @Nullable SqlNodeList distinctList,
        @Nullable SqlNodeList orderList, SqlValidatorScope scope) {
        validateAggregateFunction(aggCall, (SqlAggFunction) aggCall.getOperator());

        super.validateAggregateParams(aggCall, filter, null, orderList, scope);
    }

    /** */
    private void validateAggregateFunction(SqlCall call, SqlAggFunction aggFunction) {
        if (!SqlKind.AGGREGATE.contains(aggFunction.kind))
            throw newValidationError(call,
                IgniteResource.INSTANCE.unsupportedAggregationFunction(aggFunction.getName()));

        switch (aggFunction.kind) {
            case COUNT:
            case SUM:
            case AVG:
            case MIN:
            case MAX:

                return;
            default:
                throw newValidationError(call,
                    IgniteResource.INSTANCE.unsupportedAggregationFunction(aggFunction.getName()));
        }
    }

    /** */
    private SqlNodeList inferColumnList(SqlInsert call) {
        final SqlValidatorTable table = table(validatedNamespace(call, unknownType));

        if (table == null)
            return null;

        final TableDescriptor desc = table.unwrap(TableDescriptor.class);

        if (desc == null)
            return null;

        final SqlNodeList columnList = new SqlNodeList(SqlParserPos.ZERO);

        for (RelDataTypeField field : desc.insertRowType(typeFactory()).getFieldList())
            columnList.add(new SqlIdentifier(field.getName(), SqlParserPos.ZERO));

        return columnList;
    }

    /** */
    private void validateUpdateFields(SqlUpdate call) {
        if (call.getTargetColumnList() == null)
            return;

        final SqlValidatorNamespace ns = validatedNamespace(call, unknownType);

        final SqlValidatorTable table = table(ns);

        if (table == null)
            return;

        final TableDescriptor desc = table.unwrap(TableDescriptor.class);

        if (desc == null)
            return;

        final RelDataType baseType = table.getRowType();
        final RelOptTable relOptTable = relOptTable(ns);

        for (SqlNode node : call.getTargetColumnList()) {
            SqlIdentifier id = (SqlIdentifier) node;

            RelDataTypeField target = SqlValidatorUtil.getTargetField(
                baseType, typeFactory(), id, getCatalogReader(), relOptTable);

            if (target == null)
                throw newValidationError(id,
                    RESOURCE.unknownTargetColumn(id.toString()));

            if (!desc.isUpdateAllowed(relOptTable, target.getIndex()))
                throw newValidationError(id,
                    IgniteResource.INSTANCE.cannotUpdateField(id.toString()));
        }
    }

    /** */
    private SqlValidatorTable table(SqlValidatorNamespace ns) {
        RelOptTable relOptTable = relOptTable(ns);

        if (relOptTable != null)
            return relOptTable.unwrap(SqlValidatorTable.class);

        return ns.getTable();
    }

    /** */
    private RelOptTable relOptTable(SqlValidatorNamespace ns) {
        return SqlValidatorUtil.getRelOptTable(
            ns, getCatalogReader().unwrap(Prepare.CatalogReader.class), null, null);
    }

    /** */
    private SqlValidatorNamespace validatedNamespace(SqlNode node, RelDataType targetType) {
        SqlValidatorNamespace ns = getNamespace(node);
        validateNamespace(ns, targetType);
        return ns;
    }

    /** */
    private IgniteTypeFactory typeFactory() {
        return (IgniteTypeFactory) typeFactory;
    }
}
