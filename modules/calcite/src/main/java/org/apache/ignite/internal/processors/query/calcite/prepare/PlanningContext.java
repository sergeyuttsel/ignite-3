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

import java.util.Properties;
import java.util.function.Function;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RuleSet;
import org.apache.ignite.internal.processors.query.calcite.type.IgniteTypeFactory;
import org.jetbrains.annotations.NotNull;

import static org.apache.calcite.tools.Frameworks.createRootSchema;
import static org.apache.ignite.internal.processors.query.calcite.util.Commons.FRAMEWORK_CONFIG;

/**
 * Planning context.
 */
public final class PlanningContext implements Context {
    /** */
    private static final PlanningContext EMPTY = builder().build();

    /** */
    private final FrameworkConfig cfg;

    /** */
    private final Context parentCtx;

    /** */
    private final String locNodeId;

    /** */
    private final String originatingNodeId;

    /** */
    private final String qry;

    /** */
    private final Object[] parameters;

    /** */
    private final long topVer;

    /** */
    private final IgniteTypeFactory typeFactory;

    /** */
    private Function<RuleSet, RuleSet> rulesFilter;

    /** */
    private IgnitePlanner planner;

    /** */
    private CalciteConnectionConfig connCfg;

    /** */
    private CalciteCatalogReader catalogReader;

    /**
     * Private constructor, used by a builder.
     */
    private PlanningContext(
        FrameworkConfig cfg,
        Context parentCtx,
        String locNodeId,
        String originatingNodeId,
        String qry,
        Object[] parameters,
        long topVer
    ) {
        this.locNodeId = locNodeId;
        this.originatingNodeId = originatingNodeId;
        this.qry = qry;
        this.parameters = parameters;
        this.topVer = topVer;

        this.parentCtx = Contexts.chain(parentCtx, cfg.getContext());
        // link frameworkConfig#context() to this.
        this.cfg = Frameworks.newConfigBuilder(cfg).context(this).build();

        RelDataTypeSystem typeSys = connectionConfig().typeSystem(RelDataTypeSystem.class, cfg.getTypeSystem());
        typeFactory = new IgniteTypeFactory(typeSys);
    }

    /**
     * @return Local node ID.
     */
    public String localNodeId() {
        return locNodeId;
    }

    /**
     * @return Originating node ID (the node, who started the execution).
     */
    public String originatingNodeId() {
        return originatingNodeId == null ? locNodeId : originatingNodeId;
    }

    /**
     * @return Framework config.
     */
    public FrameworkConfig config() {
        return cfg;
    }

    /**
     * @return Query.
     */
    public String query() {
        return qry;
    }

    /**
     * @return Query parameters.
     */
    @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
    public Object[] parameters() {
        return parameters;
    }

    /**
     * @return Topology version.
     */
    public long topologyVersion() {
        return topVer;
    }

    // Helper methods
    /**
     * @return Sql operators table.
     */
    public SqlOperatorTable opTable() {
        return config().getOperatorTable();
    }

    /**
     * @return Sql conformance.
     */
    public SqlConformance conformance() {
        return cfg.getParserConfig().conformance();
    }

    /**
     * @return Planner.
     */
    public IgnitePlanner planner() {
        if (planner == null)
            planner = new IgnitePlanner(this);

        return planner;
    }

    /**
     * @return Schema name.
     */
    public String schemaName() {
        return schema().getName();
    }

    /**
     * @return Schema.
     */
    public SchemaPlus schema() {
        return cfg.getDefaultSchema();
    }

    /**
     * @return Type factory.
     */
    public IgniteTypeFactory typeFactory() {
        return typeFactory;
    }

    /**
     * @return Connection config. Defines connected user parameters like TimeZone or Locale.
     */
    public CalciteConnectionConfig connectionConfig() {
        if (connCfg != null)
            return connCfg;

        CalciteConnectionConfig connCfg = unwrap(CalciteConnectionConfig.class);

        if (connCfg != null)
            return this.connCfg = connCfg;

        Properties props = new Properties();

        props.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(),
            String.valueOf(cfg.getParserConfig().caseSensitive()));
        props.setProperty(CalciteConnectionProperty.CONFORMANCE.camelName(),
            String.valueOf(cfg.getParserConfig().conformance()));
        props.setProperty(CalciteConnectionProperty.MATERIALIZATIONS_ENABLED.camelName(),
            String.valueOf(true));

        return this.connCfg = new CalciteConnectionConfigImpl(props);
    }

    /**
     * @return New catalog reader.
     */
    public CalciteCatalogReader catalogReader() {
        if (catalogReader != null)
            return catalogReader;

        SchemaPlus dfltSchema = schema(), rootSchema = dfltSchema;

        while (rootSchema.getParentSchema() != null)
            rootSchema = rootSchema.getParentSchema();

        return catalogReader = new CalciteCatalogReader(
            CalciteSchema.from(rootSchema),
            CalciteSchema.from(dfltSchema).path(null),
            typeFactory(), connectionConfig());
    }

    /**
     * @return Cluster based on a planner and its configuration.
     */
    public RelOptCluster cluster() {
        return planner().cluster();
    }

    /** {@inheritDoc} */
    @Override public <C> C unwrap(Class<C> aCls) {
        if (aCls == getClass())
            return aCls.cast(this);

        if (aCls.isInstance(connCfg))
            return aCls.cast(connCfg);

        return parentCtx.unwrap(aCls);
    }

    /**
     * @return Context builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return Empty context.
     */
    public static PlanningContext empty() {
        return EMPTY;
    }

    /** */
    public RuleSet rules(RuleSet set) {
        return rulesFilter != null ? rulesFilter.apply(set) : set;
    }

    /**
     * @param rulesFilter Rules filter.
     */
    public void rulesFilter(Function<RuleSet, RuleSet> rulesFilter) {
        this.rulesFilter = rulesFilter;
    }

    /**
     * Planner context builder.
     */
    @SuppressWarnings("PublicInnerClass")
    public static class Builder {
        /** */
        private static final FrameworkConfig EMPTY_CONFIG =
            Frameworks.newConfigBuilder(FRAMEWORK_CONFIG)
                .defaultSchema(createRootSchema(false))
                .traitDefs()
                .build();

        /** */
        private FrameworkConfig frameworkCfg = EMPTY_CONFIG;

        /** */
        private Context parentCtx = Contexts.empty();

        /** */
        private String locNodeId;

        /** */
        private String originatingNodeId;

        /** */
        private String qry;

        /** */
        private Object[] parameters;

        /** */
        private long topVer;

        /**
         * @param locNodeId Local node ID.
         * @return Builder for chaining.
         */
        public Builder localNodeId(@NotNull String locNodeId) {
            this.locNodeId = locNodeId;
            return this;
        }

        /**
         * @param originatingNodeId Originating node ID (the node, who started the execution).
         * @return Builder for chaining.
         */
        public Builder originatingNodeId(@NotNull String originatingNodeId) {
            this.originatingNodeId = originatingNodeId;
            return this;
        }

        /**
         * @param frameworkCfg Framework config.
         * @return Builder for chaining.
         */
        public Builder frameworkConfig(@NotNull FrameworkConfig frameworkCfg) {
            this.frameworkCfg = frameworkCfg;
            return this;
        }

        /**
         * @param parentCtx Parent context.
         * @return Builder for chaining.
         */
        public Builder parentContext(@NotNull Context parentCtx) {
            this.parentCtx = parentCtx;
            return this;
        }

        /**
         * @param qry Query.
         * @return Builder for chaining.
         */
        public Builder query(@NotNull String qry) {
            this.qry = qry;
            return this;
        }

        /**
         * @param parameters Query parameters.
         * @return Builder for chaining.
         */
        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        public Builder parameters(@NotNull Object... parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * @param topVer Topology version.
         * @return Builder for chaining.
         */
        public Builder topologyVersion(long topVer) {
            this.topVer = topVer;
            return this;
        }

        /**
         * Builds planner context.
         *
         * @return Planner context.
         */
        public PlanningContext build() {
            return new PlanningContext(frameworkCfg, parentCtx, locNodeId, originatingNodeId, qry, parameters, topVer);
        }
    }
}
