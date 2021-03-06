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

package com.ethercis.validation.wrappers;

import org.openehr.schemas.v1.*;

import java.util.Map;

/**
 * Created by christian on 7/23/2016.
 */
public class CMultipleAttribute extends CConstraint  implements I_CArchetypeConstraintValidate{

    protected CMultipleAttribute(Map<String, Map<String, String>> localTerminologyLookup) {
        super(localTerminologyLookup);
    }

    @Override
    public void validate(String path, Object aValue, ARCHETYPECONSTRAINT archetypeconstraint) throws Exception {

        CMULTIPLEATTRIBUTE cmultipleattribute = (CMULTIPLEATTRIBUTE)archetypeconstraint;

        CARDINALITY cardinality = cmultipleattribute.getCardinality();
        IntervalOfInteger intervalOfInteger = cardinality.getInterval();

        //check if children cardinality is within constraint
        IntervalComparator.isWithinBoundaries(cmultipleattribute.sizeOfChildrenArray(), intervalOfInteger);

        if (cmultipleattribute.sizeOfChildrenArray() > 0){
            for (COBJECT cobject: cmultipleattribute.getChildrenArray())
                new CObject(localTerminologyLookup).validate(path, aValue, cobject);
        }

    }
}
