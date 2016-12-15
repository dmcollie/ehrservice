/*
 * Copyright (c) 2015 Christian Chevalley
 * This file is part of Project Ethercis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ethercis.aql.sql;

import com.ethercis.aql.compiler.QueryParser;
import com.ethercis.aql.compiler.TopAttributes;
import com.ethercis.aql.sql.binding.FromBinder;
import com.ethercis.aql.sql.binding.JoinBinder;
import com.ethercis.aql.sql.binding.OrderByBinder;
import com.ethercis.aql.sql.binding.SelectBinder;
import com.ethercis.aql.sql.queryImpl.CompositionAttributeQuery;
import com.ethercis.aql.sql.queryImpl.ContainsSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.*;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.jooq.RenderContext;

import static com.ethercis.jooq.pg.Tables.*;

/**
 * Perform an assembled SQL query depending on its strategy
 * <p>
 *     The strategy depends on whether the query contains elements which path depends on the context
 *     (e.g. a composition).
 *     <ul>
 *     <li>If a query contains path expression that need to be resolved, the query process consists in
 *     evaluating the path for each composition (iteration)
 *     <li>If the query contains only static fields (columns), a single query execution is done.
 *     </ul>
 * </p>
 *
 * Created by christian on 4/28/2016.
 */
public class QueryProcessor  {

    private boolean explain = false;
    DSLContext context;
    Logger logger = LogManager.getLogger(QueryProcessor.class);

    private class QuerySteps {
        private SelectQuery selectQuery;
        private Condition whereCondition;
        private String templateId;
        private CompositionAttributeQuery compositionAttributeQuery;

        public QuerySteps(SelectQuery selectQuery, Condition whereCondition, String templateId, CompositionAttributeQuery compositionAttributeQuery) {
            this.selectQuery = selectQuery;
            this.whereCondition = whereCondition;
            this.templateId = templateId;
            this.compositionAttributeQuery = compositionAttributeQuery;
        }

        public SelectQuery getSelectQuery() {
            return selectQuery;
        }

        public Condition getWhereCondition() {
            return whereCondition;
        }

        public String getTemplateId() {
            return templateId;
        }

        public CompositionAttributeQuery getCompositionAttributeQuery() {
            return compositionAttributeQuery;
        }
    }

    public QueryProcessor(DSLContext context){
        this.context = context;
    }

    public QueryProcessor(DSLContext context, boolean explain){
        this(context);
        this.explain = explain;
    }

    public Object execute(QueryParser queryParser, String serverNodeId) throws SQLException {
        return execute(queryParser, serverNodeId, false);
    }

    public Object execute(QueryParser queryParser, String serverNodeId, boolean explain) throws SQLException {

        Result<Record> result = null;
        List<Object> explainList = new ArrayList<>();
        TopAttributes topAttributes = queryParser.getTopAttributes();
        Integer count = null;
        if (topAttributes != null)
            count = topAttributes.getWindow();

//
// selectBinder.getPathResolver().resolvePaths();
//
        //store locally assembled queries for each template (and reuse it instead of rebuilding it!)
        SelectBinder.OptimizationMode optimizationMode = SelectBinder.OptimizationMode.TEMPLATE_BATCH;
        Map<String, QuerySteps> cacheQuery = new HashMap<>();

        if (queryParser.hasContainsExpression()) {
            ContainsSet containsSet = new ContainsSet(queryParser.getContainClause(), context);
            Result<?> containmentRecords = containsSet.getInSet();
            if (!containmentRecords.isEmpty()) {
                for (Record containmentRecord : containmentRecords) {
                    UUID comp_id = (UUID) containmentRecord.getValue(CONTAINMENT.COMP_ID.getName());
                    String template_id = (String) containmentRecord.getValue(ENTRY.TEMPLATE_ID.getName());
                    String label = containmentRecord.getValue(CONTAINMENT.LABEL.getName()).toString();
                    String entry_root = containmentRecord.getValue(ContainsSet.ENTRY_ROOT, String.class);

                    SelectBinder selectBinder = new SelectBinder(context, queryParser, serverNodeId, optimizationMode, entry_root);

                    SelectQuery<?> select;
                    if (cacheQuery.containsKey(template_id)) {
                        continue;

                    }
                    else {
                        select = selectBinder.bind(template_id, comp_id, label, entry_root);
                        //OPTIMIZATION 1: if expression does not require jquery resolution (template dependent), get the result in one go
//                        if (!selectBinder.containsJQueryPath()){ //can retrieve the whole set now
//                            result = fetchResultSet(select, result);
//                            break;
//                        }
                        cacheQuery.put(template_id, new QuerySteps(select, selectBinder.getWhereConditions(null), template_id, selectBinder.getCompositionAttributeQuery()));
                    }
                }

                //assemble the query from the cache
                if (!cacheQuery.isEmpty()) {
                    SelectQuery unionSetQuery = context.selectQuery();
                    boolean first = true;
                    for (QuerySteps queryStep : cacheQuery.values()) {
                        if (optimizationMode.equals(SelectBinder.OptimizationMode.NONE)) {
                            SelectQuery select = queryStep.getSelectQuery();
                            select.bind(1, null);
                            Select subselect = containsSet.getSelect();
                            select.addConditions(Operator.OR, (ENTRY.COMPOSITION_ID.in(subselect)));
                        } else if (optimizationMode.equals(SelectBinder.OptimizationMode.TEMPLATE_BATCH)) {
                            SelectQuery select = queryStep.getSelectQuery();
                            select.addConditions(ENTRY.TEMPLATE_ID.eq(queryStep.getTemplateId()));
                            Condition condition = queryStep.getWhereCondition();
                            if (condition != null)
                                select.addConditions(Operator.AND, condition);
                            select.addFrom(ENTRY);
                            new JoinBinder().addJoinClause(select, queryStep.getCompositionAttributeQuery());

                            if (first) {
                                unionSetQuery = select;
                                first = false;
                            }
                            else
                                unionSetQuery.union(select);
                        }

                    }

                    //more experimental stuff (to avoid the internal table below...)
                    OrderByBinder orderByBinder = new OrderByBinder(queryParser);
                    if (orderByBinder.hasOrderBy())
                        unionSetQuery.addOrderBy(orderByBinder.getOrderByFields());
                    if (count != null)
                        unionSetQuery.addLimit(count);
                    if (!explain)
                        result = fetchResultSet(unionSetQuery, result);
                    else
                        buildExplain(unionSetQuery, explainList);
                }
                if (explain)
                    return explainList;
                else
                    return result;
                }
            else {
                if (explain)
                    return explainList;
                else
                    return result;
            }
        }
        else {
            SelectBinder selectBinder = new SelectBinder(context, queryParser, serverNodeId, SelectBinder.OptimizationMode.TEMPLATE_BATCH, null);
            SelectQuery<?> select = selectBinder.bind(queryParser.getInSetExpression(), count, queryParser.getOrderAttributes());
            new FromBinder().addFromClause(select, selectBinder.getCompositionAttributeQuery(), queryParser);
            new JoinBinder().addJoinClause(select, selectBinder.getCompositionAttributeQuery());
            if (!explain)
                result = (Result<Record>)select.fetch();
            else
                buildExplain(select, explainList);
        }
        if (explain)
            return explainList;
        else
            return result;
    }

    public Result<?> perform(Select<?> select) throws SQLException {
        return select.fetch();
    }

    private Result<Record> fetchResultSet(Select<?> select, Result<Record> result) throws SQLException {
        Result<Record> intermediary = (Result<Record>) select.fetch();
        if (result != null) {
            result.addAll(intermediary);
        } else if (intermediary != null) {
            result = intermediary;
        }
        return result;
    }

    private List buildExplain(Select<?> select, List explainList){
        DSLContext pretty = DSL.using(context.dialect(), new Settings().withRenderFormatted(true));
        String sql = pretty.render(select);
        List<String> details = new ArrayList<>();
        details.add(sql);
        for (Param<?> parameter: select.getParams().values()){
            details.add(parameter.getValue().toString());
        }
        explainList.add(details);
        return explainList;
    }
}
