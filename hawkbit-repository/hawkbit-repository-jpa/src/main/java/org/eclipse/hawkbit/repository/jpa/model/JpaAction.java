/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.model;

import java.io.Serial;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.eclipse.hawkbit.repository.MaintenanceScheduleHelper;
import org.eclipse.hawkbit.repository.event.remote.entity.ActionCreatedEvent;
import org.eclipse.hawkbit.repository.event.remote.entity.ActionUpdatedEvent;
import org.eclipse.hawkbit.repository.model.Action;
import org.eclipse.hawkbit.repository.model.ActionStatus;
import org.eclipse.hawkbit.repository.model.BaseEntity;
import org.eclipse.hawkbit.repository.model.DistributionSet;
import org.eclipse.hawkbit.repository.model.Rollout;
import org.eclipse.hawkbit.repository.model.RolloutGroup;
import org.eclipse.hawkbit.repository.model.Target;
import org.eclipse.hawkbit.repository.model.helper.EventPublisherHolder;

/**
 * JPA implementation of {@link Action}.
 */
@Table(
        name = "sp_action",
        indexes = {
                @Index(name = "sp_idx_action_01", columnList = "tenant,distribution_set"),
                @Index(name = "sp_idx_action_02", columnList = "tenant,target,active"),
                @Index(name = "sp_idx_action_prim", columnList = "tenant,id")
        })
@NamedEntityGraphs({
        @NamedEntityGraph(name = "Action.ds", attributeNodes = { @NamedAttributeNode("distributionSet") }),
        @NamedEntityGraph(name = "Action.all", attributeNodes = {
                @NamedAttributeNode("distributionSet"),
                @NamedAttributeNode(value = "target", subgraph = "target.ds") },
                subgraphs = @NamedSubgraph(name = "target.ds", attributeNodes = @NamedAttributeNode("assignedDistributionSet")))
})
@Entity
// exception squid:S2160 - BaseEntity equals/hashcode is handling correctly for sub entities
@SuppressWarnings("squid:S2160")
public class JpaAction extends AbstractJpaTenantAwareBaseEntity implements Action, EventAwareEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "distribution_set", nullable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.CONSTRAINT, name = "fk_action_ds"))
    @NotNull
    private JpaDistributionSet distributionSet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "target", updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.CONSTRAINT, name = "fk_targ_act_hist_targ"))
    @NotNull
    private JpaTarget target;

    @Column(name = "active")
    private boolean active;

    @Converter
    public static class ActionTypeConverter extends MapTypeConverter<ActionType, Integer> {

        public ActionTypeConverter() {
            super(Map.of(
                    ActionType.FORCED, 0,
                    ActionType.SOFT, 1,
                    ActionType.TIMEFORCED, 2,
                    ActionType.DOWNLOAD_ONLY, 3
            ));
        }
    }
    @Column(name = "action_type", nullable = false)
    @Convert(converter = ActionTypeConverter.class)
    @NotNull
    private ActionType actionType;

    @Column(name = "forced_time")
    private long forcedTime;

    @Column(name = "weight")
    @Min(Action.WEIGHT_MIN)
    @Max(Action.WEIGHT_MAX)
    private Integer weight;

    @Converter
    public static class StatusConverter extends MapTypeConverter<Status, Integer> {

        public StatusConverter() {
            super(new HashMap<>() {{
                put(Status.FINISHED, 0);
                put(Status.ERROR, 1);
                put(Status.WARNING, 2);
                put(Status.RUNNING, 3);
                put(Status.CANCELED, 4);
                put(Status.CANCELING, 5);
                put(Status.RETRIEVED, 6);
                put(Status.DOWNLOAD, 7);
                put(Status.SCHEDULED, 8);
                put(Status.CANCEL_REJECTED, 9);
                put(Status.DOWNLOADED, 10);
                put(Status.WAIT_FOR_CONFIRMATION, 11);
            }});
        }
    }
    @Column(name = "status", nullable = false)
    @Convert(converter = StatusConverter.class)
    @NotNull
    private Status status;

    @OneToMany(mappedBy = "action", targetEntity = JpaActionStatus.class, fetch = FetchType.LAZY, cascade = { CascadeType.REMOVE })
    private List<JpaActionStatus> actionStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "rolloutgroup", updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.CONSTRAINT, name = "fk_action_rolloutgroup"))
    private JpaRolloutGroup rolloutGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "rollout", updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.CONSTRAINT, name = "fk_action_rollout"))
    private JpaRollout rollout;

    @Column(name = "maintenance_cron_schedule", updatable = false, length = Action.MAINTENANCE_WINDOW_SCHEDULE_LENGTH)
    private String maintenanceWindowSchedule;

    @Column(name = "maintenance_duration", updatable = false, length = Action.MAINTENANCE_WINDOW_DURATION_LENGTH)
    private String maintenanceWindowDuration;

    @Column(name = "maintenance_time_zone", updatable = false, length = Action.MAINTENANCE_WINDOW_TIMEZONE_LENGTH)
    private String maintenanceWindowTimeZone;

    @Column(name = "external_ref", length = Action.EXTERNAL_REF_MAX_LENGTH)
    private String externalRef;

    @Column(name = "initiated_by", updatable = false, nullable = false, length = USERNAME_FIELD_LENGTH)
    private String initiatedBy;

    @Column(name = "last_action_status_code", nullable = true, updatable = true)
    private Integer lastActionStatusCode;

    @Override
    public DistributionSet getDistributionSet() {
        return distributionSet;
    }

    public void setDistributionSet(final DistributionSet distributionSet) {
        this.distributionSet = (JpaDistributionSet) distributionSet;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    public void setStatus(final Status status) {
        this.status = status;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void setActive(final boolean active) {
        this.active = active;
    }

    @Override
    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(final ActionType actionType) {
        this.actionType = actionType;
    }

    @Override
    public Target getTarget() {
        return target;
    }

    public void setTarget(final Target target) {
        this.target = (JpaTarget) target;
    }

    @Override
    public long getForcedTime() {
        return forcedTime;
    }

    public void setForcedTime(final long forcedTime) {
        this.forcedTime = forcedTime;
    }

    @Override
    public Optional<Integer> getWeight() {
        return Optional.ofNullable(weight);
    }

    public void setWeight(final Integer weight) {
        this.weight = weight;
    }

    @Override
    public RolloutGroup getRolloutGroup() {
        return rolloutGroup;
    }

    public void setRolloutGroup(final RolloutGroup rolloutGroup) {
        this.rolloutGroup = (JpaRolloutGroup) rolloutGroup;
    }

    @Override
    public Rollout getRollout() {
        return rollout;
    }

    public void setRollout(final Rollout rollout) {
        this.rollout = (JpaRollout) rollout;
    }

    @Override
    public String getMaintenanceWindowSchedule() {
        return maintenanceWindowSchedule;
    }

    /**
     * Sets the maintenance schedule.
     *
     * @param maintenanceWindowSchedule is a cron expression to be used for scheduling.
     */
    public void setMaintenanceWindowSchedule(final String maintenanceWindowSchedule) {
        this.maintenanceWindowSchedule = maintenanceWindowSchedule;
    }

    @Override
    public String getMaintenanceWindowDuration() {
        return maintenanceWindowDuration;
    }

    /**
     * Sets the maintenance window duration.
     *
     * @param maintenanceWindowDuration is the duration of an available maintenance schedule in
     *         HH:mm:ss format.
     */
    public void setMaintenanceWindowDuration(final String maintenanceWindowDuration) {
        this.maintenanceWindowDuration = maintenanceWindowDuration;
    }

    @Override
    public String getMaintenanceWindowTimeZone() {
        return maintenanceWindowTimeZone;
    }

    /**
     * Sets the time zone to be used for maintenance window.
     *
     * @param maintenanceWindowTimeZone is the time zone specified as +/-hh:mm offset from UTC for
     *         example +02:00 for CET summer time and +00:00 for UTC. The
     *         start time of a maintenance window calculated based on the
     *         cron expression is relative to this time zone.
     */
    public void setMaintenanceWindowTimeZone(final String maintenanceWindowTimeZone) {
        this.maintenanceWindowTimeZone = maintenanceWindowTimeZone;
    }

    @Override
    public String getExternalRef() {
        return externalRef;
    }

    @Override
    public void setExternalRef(final String externalRef) {
        this.externalRef = externalRef;
    }

    @Override
    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(final String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    @Override
    public Optional<Integer> getLastActionStatusCode() {
        return Optional.ofNullable(lastActionStatusCode);
    }

    @Override
    public Optional<ZonedDateTime> getMaintenanceWindowStartTime() {
        return MaintenanceScheduleHelper.getNextMaintenanceWindow(maintenanceWindowSchedule, maintenanceWindowDuration,
                maintenanceWindowTimeZone);
    }

    @Override
    public boolean hasMaintenanceSchedule() {
        return this.maintenanceWindowSchedule != null;
    }

    @Override
    public boolean isMaintenanceScheduleLapsed() {
        return !getMaintenanceWindowStartTime().isPresent();
    }

    @Override
    public boolean isMaintenanceWindowAvailable() {
        if (!hasMaintenanceSchedule()) {
            // if there is no defined maintenance schedule, a window is always
            // available.
            return true;
        } else if (isMaintenanceScheduleLapsed()) {
            // if a defined maintenance schedule has lapsed, a window is never
            // available.
            return false;
        } else {
            final ZonedDateTime now = ZonedDateTime.now(ZoneOffset.of(maintenanceWindowTimeZone));
            final Optional<ZonedDateTime> start = getMaintenanceWindowStartTime();
            final Optional<ZonedDateTime> end = getMaintenanceWindowEndTime();

            if (start.isPresent() && end.isPresent()) {
                return now.isAfter(start.get()) && now.isBefore(end.get());
            } else {
                return false;
            }
        }
    }

    public boolean isWaitingConfirmation() {
        return status == Status.WAIT_FOR_CONFIRMATION;
    }

    public void setLastActionStatusCode(final Integer lastActionStatusCode) {
        this.lastActionStatusCode = lastActionStatusCode;
    }

    public List<ActionStatus> getActionStatus() {
        if (actionStatus == null) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(actionStatus);
    }

    @Override
    public String toString() {
        return "JpaAction [distributionSet=" + distributionSet.getId() + ", version=" + getOptLockRevision() + ", id="
                + getId() + ", actionType=" + getActionType() + ", weight=" + getWeight() + ", isActive=" + isActive()
                + ",  createdAt=" + getCreatedAt() + ", lastModifiedAt=" + getLastModifiedAt() + ", status="
                + getStatus().name() + "]";
    }

    @Override
    public void fireCreateEvent() {
        EventPublisherHolder.getInstance().getEventPublisher()
                .publishEvent(new ActionCreatedEvent(this, BaseEntity.getIdOrNull(target),
                        BaseEntity.getIdOrNull(rollout), BaseEntity.getIdOrNull(rolloutGroup),
                        EventPublisherHolder.getInstance().getApplicationId()));
    }

    @Override
    public void fireUpdateEvent() {
        EventPublisherHolder.getInstance().getEventPublisher()
                .publishEvent(new ActionUpdatedEvent(this, BaseEntity.getIdOrNull(target),
                        BaseEntity.getIdOrNull(rollout), BaseEntity.getIdOrNull(rolloutGroup),
                        EventPublisherHolder.getInstance().getApplicationId()));
    }

    @Override
    public void fireDeleteEvent() {
        // there is no action deletion
    }

    /**
     * Returns the end time of next available or active maintenance window for
     * the {@link Action} as {@link ZonedDateTime}. If a maintenance window is
     * already active, the end time of currently active window is returned.
     *
     * @return the end time of window as { @link Optional<ZonedDateTime>}.
     */
    private Optional<ZonedDateTime> getMaintenanceWindowEndTime() {
        return getMaintenanceWindowStartTime()
                .map(start -> start.plus(MaintenanceScheduleHelper.convertToISODuration(maintenanceWindowDuration)));
    }

    private static class MapTypeConverter<JAVA_TYPE extends Enum, DB_TYPE> implements AttributeConverter<JAVA_TYPE, DB_TYPE> {

        private final Map<JAVA_TYPE, DB_TYPE> javaToDbMap;
        private final Map<DB_TYPE, JAVA_TYPE> dbToJavaMap;

        protected MapTypeConverter(final Map<JAVA_TYPE, DB_TYPE> javaToDbMap) {
            this.javaToDbMap = javaToDbMap;
            this.dbToJavaMap = javaToDbMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
            if (javaToDbMap.size() != dbToJavaMap.size()) {
                throw new IllegalArgumentException("Duplicate values in javaToDbMap");
            }
        }

        @Override
        public DB_TYPE convertToDatabaseColumn(final JAVA_TYPE attribute) {
            return javaToDbMap.get(attribute);
        }

        @Override
        public JAVA_TYPE convertToEntityAttribute(final DB_TYPE dbData) {
            return dbToJavaMap.get(dbData);
        }
    }
}