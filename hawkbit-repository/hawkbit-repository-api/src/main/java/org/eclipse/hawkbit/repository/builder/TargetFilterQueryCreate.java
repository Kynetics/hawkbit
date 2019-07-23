/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.repository.builder;

import java.util.Optional;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.eclipse.hawkbit.repository.model.Action.ActionType;
import org.eclipse.hawkbit.repository.model.BaseEntity;
import org.eclipse.hawkbit.repository.model.DistributionSet;
import org.eclipse.hawkbit.repository.model.NamedEntity;
import org.eclipse.hawkbit.repository.model.TargetFilterQuery;

/**
 * Builder to create a new {@link TargetFilterQuery} entry. Defines all fields
 * that can be set at creation time. Other fields are set by the repository
 * automatically, e.g. {@link BaseEntity#getCreatedAt()}.
 *
 */
public interface TargetFilterQueryCreate {
    /**
     * @param name
     *            of {@link TargetFilterQuery#getName()}
     * @return updated builder instance
     */
    TargetFilterQueryCreate name(@Size(min = 1, max = NamedEntity.NAME_MAX_SIZE) @NotNull String name);

    /**
     * @param query
     *            of {@link TargetFilterQuery#getQuery()}
     * @return updated builder instance
     */
    TargetFilterQueryCreate query(@Size(min = 1, max = TargetFilterQuery.QUERY_MAX_SIZE) @NotNull String query);

    /**
     * @param distributionSet
     *            for {@link TargetFilterQuery#getAutoAssignDistributionSet()}
     * @return updated builder instance
     */
    default TargetFilterQueryCreate autoAssignDistributionSet(final DistributionSet distributionSet) {
        return autoAssignDistributionSet(Optional.ofNullable(distributionSet).map(DistributionSet::getId).orElse(null));
    }

    /**
     * @param dsId
     *            for {@link TargetFilterQuery#getAutoAssignDistributionSet()}
     * @return updated builder instance
     */
    TargetFilterQueryCreate autoAssignDistributionSet(Long dsId);

    /**
     * @param actionType
     *            for {@link TargetFilterQuery#getAutoAssignActionType()}
     * @return updated builder instance
     */
    TargetFilterQueryCreate autoAssignActionType(ActionType actionType);

    /**
     * @return peek on current state of {@link TargetFilterQuery} in the builder
     */
    TargetFilterQuery build();
}