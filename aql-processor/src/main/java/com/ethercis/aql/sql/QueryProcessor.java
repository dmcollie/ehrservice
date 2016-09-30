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
import com.ethercis.aql.sql.binding.OrderByBinder;
import com.ethercis.aql.sql.binding.SelectBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

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
 * TODO: optimize the query with path expression
 * Created by christian on 4/28/2016.
 */
public class QueryProcessor  {

    DSLContext context;
    Logger logger = LogManager.getLogger(QueryProcessor.class);

    public QueryProcessor(DSLContext context){
        this.context = context;
    }

//    public QueryProcessor(I_DomainAccess domainAccess){
////        super(domainAccess);
////    }

    public List<Record> execute(QueryParser queryParser, String serverNodeId) throws SQLException {
        SelectBinder selectBinder = new SelectBinder(context, queryParser, serverNodeId);
        Result<Record> result = null;
        TopAttributes topAttributes = queryParser.getTopAttributes();
        Integer count = null;
        if (topAttributes != null)
            count = topAttributes.getWindow();

//TODO: OPTIMIZATION: resolve path for COMPOSITION only and generate a corresponding WHERE clause to use in getInSet()
// selectBinder.getPathResolver().resolvePaths();
//
        if (queryParser.hasPathExpr()) {
            Result<?> records = queryParser.getInSet();
            if (!records.isEmpty()) {
                for (Record record : queryParser.getInSet()) {
                    UUID comp_id = (UUID) record.getValue("comp_id");
                    SelectQuery<?> select = selectBinder.bind(comp_id);
                    Result<Record> intermediary = (Result<Record>) select.fetch();
                    if (result != null) {
                        result.addAll(intermediary);
                    } else if (intermediary != null) {
                        result = intermediary;
                    }
                }

                OrderByBinder orderByBinder = new OrderByBinder(queryParser);
                //at this stage we can apply aggregate functions if any
                if (!result.isEmpty()) {
                    Table<?> table = DSL.table(result);
                    SelectQuery<Record> selectQuery = context.selectQuery();
                    selectQuery.addSelect(table.fields());
                    if (selectBinder.hasEhrIdExpression()) {
                        Name name = DSL.name(selectBinder.getEhrIdAlias());
                        Field<?> ehrIdField = table.field(name);
                        selectQuery.addDistinctOn(ehrIdField);
                    }
                    selectQuery.addFrom(table);
                    if (orderByBinder.hasOrderBy())
                        selectQuery.addOrderBy(orderByBinder.getOrderByFields());
                    if (count != null)
                        selectQuery.addLimit(count);

                    return selectQuery.fetch();
                }
                else
                    return result;
            }
        }
        else {
//            SelectQuery<?> select = selectBinder.bind(queryParser.getInSet(count));
            SelectQuery<?> select = selectBinder.bind(queryParser.getInSetExpression(), count, queryParser.getOrderAttributes());
            result = (Result<Record>)select.fetch();
        }
        return result;
    }

    public Result<?> perform(Select<?> select) throws SQLException {
        return select.fetch();
    }
}