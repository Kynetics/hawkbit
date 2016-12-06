/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.repository.jpa;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.hawkbit.repository.ActionStatusFields;
import org.eclipse.hawkbit.repository.event.remote.TargetAssignDistributionSetEvent;
import org.eclipse.hawkbit.repository.event.remote.entity.CancelTargetAssignmentEvent;
import org.eclipse.hawkbit.repository.exception.ForceQuitActionNotAllowedException;
import org.eclipse.hawkbit.repository.exception.IncompleteDistributionSetException;
import org.eclipse.hawkbit.repository.jpa.configuration.Constants;
import org.eclipse.hawkbit.repository.jpa.model.JpaAction;
import org.eclipse.hawkbit.repository.jpa.model.JpaActionStatus;
import org.eclipse.hawkbit.repository.jpa.model.JpaDistributionSet;
import org.eclipse.hawkbit.repository.jpa.model.JpaTarget;
import org.eclipse.hawkbit.repository.model.Action;
import org.eclipse.hawkbit.repository.model.Action.ActionType;
import org.eclipse.hawkbit.repository.model.Action.Status;
import org.eclipse.hawkbit.repository.model.ActionWithStatusCount;
import org.eclipse.hawkbit.repository.model.DistributionSet;
import org.eclipse.hawkbit.repository.model.DistributionSetAssignmentResult;
import org.eclipse.hawkbit.repository.model.DistributionSetTag;
import org.eclipse.hawkbit.repository.model.SoftwareModule;
import org.eclipse.hawkbit.repository.model.Target;
import org.eclipse.hawkbit.repository.model.TargetUpdateStatus;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import ru.yandex.qatools.allure.annotations.Description;
import ru.yandex.qatools.allure.annotations.Features;
import ru.yandex.qatools.allure.annotations.Stories;

/**
 * Test class testing the functionality of triggering a deployment of
 * {@link DistributionSet}s to {@link Target}s.
 *
 */
@Features("Component Tests - Repository")
@Stories("Deployment Management")
public class DeploymentManagementTest extends AbstractJpaIntegrationTest {

    private EventHandlerStub eventHandlerStub;

    private CancelEventHandlerStub cancelEventHandlerStub;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Before
    public void addHandler() {
        eventHandlerStub = new EventHandlerStub();
        applicationContext.addApplicationListener(eventHandlerStub);

        cancelEventHandlerStub = new CancelEventHandlerStub();
        applicationContext.addApplicationListener(cancelEventHandlerStub);
    }

    @Test
    @Description("Test verifies that the repistory retrieves the action including all defined (lazy) details.")
    public void findActionWithLazyDetails() {
        final DistributionSet testDs = testdataFactory.createDistributionSet("TestDs", "1.0",
                new ArrayList<DistributionSetTag>());
        final List<Target> testTarget = testdataFactory.createTargets(1);
        // one action with one action status is generated
        final Long actionId = assignDistributionSet(testDs, testTarget).getActions().get(0);
        final Action action = deploymentManagement.findActionWithDetails(actionId);

        assertThat(action.getDistributionSet()).as("DistributionSet in action").isNotNull();
        assertThat(action.getTarget()).as("Target in action").isNotNull();
        assertThat(action.getTarget().getAssignedDistributionSet()).as("AssignedDistributionSet of target in action")
                .isNotNull();

    }

    @Test
    @Description("Test verifies that the custom query to find all actions include the count of action status is working correctly")
    public void findActionsWithStatusCountByTarget() {
        final DistributionSet testDs = testdataFactory.createDistributionSet("TestDs", "1.0",
                new ArrayList<DistributionSetTag>());
        final List<Target> testTarget = testdataFactory.createTargets(1);
        // one action with one action status is generated
        final Action action = deploymentManagement
                .findActionWithDetails(assignDistributionSet(testDs, testTarget).getActions().get(0));
        // save 2 action status
        actionStatusRepository.save(new JpaActionStatus(action, Status.RETRIEVED, System.currentTimeMillis()));
        actionStatusRepository.save(new JpaActionStatus(action, Status.RUNNING, System.currentTimeMillis()));

        final List<ActionWithStatusCount> findActionsWithStatusCountByTarget = deploymentManagement
                .findActionsWithStatusCountByTargetOrderByIdDesc(testTarget.get(0));

        assertThat(findActionsWithStatusCountByTarget).as("wrong action size").hasSize(1);
        assertThat(findActionsWithStatusCountByTarget.get(0).getActionStatusCount()).as("wrong action status size")
                .isEqualTo(3);
    }

    @Test
    @Description("Ensures that distribution sets can assigned and unassigned to a  distribution set tag. Not exists  distribution set will be ignored for the assignment.")
    public void assignAndUnassignDistributionSetToTag() {
        final List<Long> assignDS = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assignDS.add(testdataFactory.createDistributionSet("DS" + i, "1.0", new ArrayList<DistributionSetTag>())
                    .getId());
        }
        // not exists
        assignDS.add(Long.valueOf(100));
        final DistributionSetTag tag = tagManagement
                .createDistributionSetTag(entityFactory.tag().create().name("Tag1"));

        final List<DistributionSet> assignedDS = distributionSetManagement.assignTag(assignDS, tag);
        assertThat(assignedDS.size()).as("assigned ds has wrong size").isEqualTo(4);
        assignedDS.forEach(ds -> assertThat(ds.getTags().size()).as("ds has wrong tag size").isEqualTo(1));

        DistributionSetTag findDistributionSetTag = tagManagement.findDistributionSetTag("Tag1");
        assertThat(assignedDS.size()).as("assigned ds has wrong size")
                .isEqualTo(findDistributionSetTag.getAssignedToDistributionSet().size());

        assertThat(distributionSetManagement.unAssignTag(Long.valueOf(100), findDistributionSetTag))
                .as("unassign tag result should be null").isNull();

        final DistributionSet unAssignDS = distributionSetManagement.unAssignTag(assignDS.get(0),
                findDistributionSetTag);
        assertThat(unAssignDS.getId()).as("unassigned ds is wrong").isEqualTo(assignDS.get(0));
        assertThat(unAssignDS.getTags().size()).as("unassigned ds has wrong tag size").isEqualTo(0);
        findDistributionSetTag = tagManagement.findDistributionSetTag("Tag1");
        assertThat(findDistributionSetTag.getAssignedToDistributionSet().size()).as("ds tag ds has wrong ds size")
                .isEqualTo(3);

        final List<DistributionSet> unAssignTargets = distributionSetManagement
                .unAssignAllDistributionSetsByTag(findDistributionSetTag);
        findDistributionSetTag = tagManagement.findDistributionSetTag("Tag1");
        assertThat(findDistributionSetTag.getAssignedToDistributionSet().size()).as("ds tag has wrong ds size")
                .isEqualTo(0);
        assertThat(unAssignTargets.size()).as("unassigned target has wrong size").isEqualTo(3);
        unAssignTargets
                .forEach(target -> assertThat(target.getTags().size()).as("target has wrong tag size").isEqualTo(0));
    }

    @Test
    @Description("Test verifies that an assignment with automatic cancelation works correctly even if the update is split into multiple partitions on the database.")
    public void multiAssigmentHistoryOverMultiplePagesResultsInTwoActiveAction() {

        final DistributionSet cancelDs = testdataFactory.createDistributionSet("Canceled DS", "1.0",
                Collections.emptyList());

        final DistributionSet cancelDs2 = testdataFactory.createDistributionSet("Canceled DS", "1.2",
                Collections.emptyList());

        List<Target> targets = testdataFactory.createTargets(Constants.MAX_ENTRIES_IN_STATEMENT + 10);

        targets = assignDistributionSet(cancelDs, targets).getAssignedEntity();
        targets = assignDistributionSet(cancelDs2, targets).getAssignedEntity();

        targetManagement.findAllTargetIds().forEach(targetIdName -> {
            assertThat(deploymentManagement.findActiveActionsByTarget(
                    targetManagement.findTargetByControllerID(targetIdName.getControllerId())))
                            .as("active action has wrong size").hasSize(2);
        });
    }

    @Test
    @Description("Cancels multiple active actions on a target. Expected behaviour is that with two active "
            + "actions after canceling the second active action the first one is still running as it is not touched by the cancelation. After canceling the first one "
            + "also the target goes back to IN_SYNC as no open action is left.")
    public void manualCancelWithMultipleAssignmentsCancelLastOneFirst() {
        final Action action = prepareFinishedUpdate("4712", "installed", true);
        final Target target = action.getTarget();
        final DistributionSet dsFirst = testdataFactory.createDistributionSet("", true);
        final DistributionSet dsSecond = testdataFactory.createDistributionSet("2", true);
        final DistributionSet dsInstalled = action.getDistributionSet();

        // check initial status
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("target has update status").isEqualTo(TargetUpdateStatus.IN_SYNC);

        // assign the two sets in a row
        JpaAction firstAction = assignSet(target, dsFirst);
        JpaAction secondAction = assignSet(target, dsSecond);

        assertThat(actionRepository.findAll()).as("wrong size of actions").hasSize(3);
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(5);

        // we cancel second -> back to first
        deploymentManagement.cancelAction(secondAction,
                targetManagement.findTargetByControllerID(target.getControllerId()));
        secondAction = (JpaAction) deploymentManagement.findActionWithDetails(secondAction.getId());
        // confirm cancellation
        controllerManagement.addCancelActionStatus(
                entityFactory.actionStatus().create(secondAction.getId()).status(Status.CANCELED));
        assertThat(actionStatusRepository.findAll()).as("wrong size of actions status").hasSize(7);
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet()).as("wrong ds")
                .isEqualTo(dsFirst);
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong update status").isEqualTo(TargetUpdateStatus.PENDING);

        // we cancel first -> back to installed
        deploymentManagement.cancelAction(firstAction,
                targetManagement.findTargetByControllerID(target.getControllerId()));
        firstAction = (JpaAction) deploymentManagement.findActionWithDetails(firstAction.getId());
        // confirm cancellation
        controllerManagement.addCancelActionStatus(
                entityFactory.actionStatus().create(firstAction.getId()).status(Status.CANCELED));
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(9);
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet())
                .as("wrong assigned ds").isEqualTo(dsInstalled);
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong update status").isEqualTo(TargetUpdateStatus.IN_SYNC);
    }

    @Test
    @Description("Cancels multiple active actions on a target. Expected behaviour is that with two active "
            + "actions after canceling the first active action the system switched to second one. After canceling this one "
            + "also the target goes back to IN_SYNC as no open action is left.")
    public void manualCancelWithMultipleAssignmentsCancelMiddleOneFirst() {
        final Action action = prepareFinishedUpdate("4712", "installed", true);
        final Target target = action.getTarget();
        final DistributionSet dsFirst = testdataFactory.createDistributionSet("", true);
        final DistributionSet dsSecond = testdataFactory.createDistributionSet("2", true);
        final DistributionSet dsInstalled = action.getDistributionSet();

        // check initial status
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong update status").isEqualTo(TargetUpdateStatus.IN_SYNC);

        // assign the two sets in a row
        JpaAction firstAction = assignSet(target, dsFirst);
        JpaAction secondAction = assignSet(target, dsSecond);

        assertThat(actionRepository.findAll()).as("wrong size of actions").hasSize(3);
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(5);

        // we cancel first -> second is left
        deploymentManagement.cancelAction(firstAction,
                targetManagement.findTargetByControllerID(target.getControllerId()));
        // confirm cancellation
        firstAction = (JpaAction) deploymentManagement.findActionWithDetails(firstAction.getId());
        controllerManagement.addCancelActionStatus(
                entityFactory.actionStatus().create(firstAction.getId()).status(Status.CANCELED));
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(7);
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet())
                .as("wrong assigned ds").isEqualTo(dsSecond);
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong target update status").isEqualTo(TargetUpdateStatus.PENDING);

        // we cancel second -> remain assigned until finished cancellation
        deploymentManagement.cancelAction(secondAction,
                targetManagement.findTargetByControllerID(target.getControllerId()));
        secondAction = (JpaAction) deploymentManagement.findActionWithDetails(secondAction.getId());
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(8);
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet())
                .as("wrong assigned ds").isEqualTo(dsSecond);
        // confirm cancellation
        controllerManagement.addCancelActionStatus(
                entityFactory.actionStatus().create(secondAction.getId()).status(Status.CANCELED));
        // cancelled success -> back to dsInstalled
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet())
                .as("wrong installed ds").isEqualTo(dsInstalled);
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong target info update status").isEqualTo(TargetUpdateStatus.IN_SYNC);
    }

    @Test
    @Description("Force Quit an Assignment. Expected behaviour is that the action is canceled and is marked as deleted. The assigned Software module")
    public void forceQuitSetActionToInactive() throws InterruptedException {
        final Action action = prepareFinishedUpdate("4712", "installed", true);
        Target target = action.getTarget();
        final DistributionSet dsInstalled = action.getDistributionSet();

        final DistributionSet ds = testdataFactory.createDistributionSet("newDS", true);

        // verify initial status
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong target info update status").isEqualTo(TargetUpdateStatus.IN_SYNC);

        Action assigningAction = assignSet(target, ds);

        // verify assignment
        assertThat(actionRepository.findAll()).as("wrong size of action").hasSize(2);
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(4);

        target = targetManagement.findTargetByControllerID(target.getControllerId());

        // force quit assignment
        deploymentManagement.cancelAction(assigningAction, target);
        assigningAction = deploymentManagement.findActionWithDetails(assigningAction.getId());

        deploymentManagement.forceQuitAction(assigningAction);

        assigningAction = deploymentManagement.findActionWithDetails(assigningAction.getId());

        // verify
        assertThat(assigningAction.getStatus()).as("wrong size of status").isEqualTo(Status.CANCELED);
        assertThat(targetManagement.findTargetByControllerID("4712").getAssignedDistributionSet())
                .as("wrong assigned ds").isEqualTo(dsInstalled);
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong target update status").isEqualTo(TargetUpdateStatus.IN_SYNC);
    }

    @Test
    @Description("Force Quit an not canceled Assignment. Expected behaviour is that the action can not be force quit and there is thrown an exception.")
    public void forceQuitNotAllowedThrowsException() {
        final Action action = prepareFinishedUpdate("4712", "installed", true);
        final Target target = action.getTarget();

        final DistributionSet ds = testdataFactory.createDistributionSet("newDS", true);

        // verify initial status
        assertThat(targetManagement.findTargetByControllerID("4712").getTargetInfo().getUpdateStatus())
                .as("wrong update status").isEqualTo(TargetUpdateStatus.IN_SYNC);

        final Action assigningAction = assignSet(target, ds);

        // verify assignment
        assertThat(actionRepository.findAll()).as("wrong size of action").hasSize(2);
        assertThat(actionStatusRepository.findAll()).as("wrong size of action status").hasSize(4);

        // force quit assignment
        try {
            deploymentManagement.forceQuitAction(assigningAction);
            fail("expected ForceQuitActionNotAllowedException");
        } catch (final ForceQuitActionNotAllowedException ex) {
        }
    }

    private JpaAction assignSet(final Target target, final DistributionSet ds) {
        assignDistributionSet(ds.getId(), target.getControllerId());
        assertThat(
                targetManagement.findTargetByControllerID(target.getControllerId()).getTargetInfo().getUpdateStatus())
                        .as("wrong update status").isEqualTo(TargetUpdateStatus.PENDING);
        assertThat(targetManagement.findTargetByControllerID(target.getControllerId()).getAssignedDistributionSet())
                .as("wrong assigned ds").isEqualTo(ds);
        final JpaAction action = actionRepository
                .findByTargetAndDistributionSet(pageReq, (JpaTarget) target, (JpaDistributionSet) ds).getContent()
                .get(0);
        assertThat(action).as("action should not be null").isNotNull();
        return action;
    }

    /**
     * test a simple deployment by calling the
     * {@link TargetRepository#assignDistributionSet(DistributionSet, Iterable)}
     * and checking the active action and the action history of the targets.
     *
     * @throws InterruptedException
     */
    @Test
    @Description("Simple deployment or distribution set to target assignment test.")
    public void assignDistributionSet2Targets() throws InterruptedException {
        eventHandlerStub.setExpectedNumberOfEvents(20);

        final String myCtrlIDPref = "myCtrlID";
        final Iterable<Target> savedNakedTargets = testdataFactory.createTargets(10, myCtrlIDPref, "first description");

        final String myDeployedCtrlIDPref = "myDeployedCtrlID";
        List<Target> savedDeployedTargets = testdataFactory.createTargets(20, myDeployedCtrlIDPref,
                "first description");

        final DistributionSet ds = testdataFactory.createDistributionSet("");

        assignDistributionSet(ds, savedDeployedTargets);

        // verify that one Action for each assignDistributionSet
        assertThat(actionRepository.findAll(pageReq).getNumberOfElements()).as("wrong size of actions").isEqualTo(20);

        final Iterable<Target> allFoundTargets = targetManagement.findTargetsAll(pageReq).getContent();

        // get final updated version of targets
        savedDeployedTargets = targetManagement.findTargetByControllerID(
                savedDeployedTargets.stream().map(target -> target.getControllerId()).collect(Collectors.toList()));

        assertThat(allFoundTargets).as("founded targets are wrong").containsAll(savedDeployedTargets)
                .containsAll(savedNakedTargets);
        assertThat(savedDeployedTargets).as("saved target are wrong")
                .doesNotContain(Iterables.toArray(savedNakedTargets, Target.class));
        assertThat(savedNakedTargets).as("saved target are wrong")
                .doesNotContain(Iterables.toArray(savedDeployedTargets, Target.class));

        for (final Target myt : savedNakedTargets) {
            final Target t = targetManagement.findTargetByControllerID(myt.getControllerId());
            assertThat(deploymentManagement.findActionsByTarget(t)).as("action should be empty").isEmpty();
        }

        for (final Target myt : savedDeployedTargets) {
            final Target t = targetManagement.findTargetByControllerID(myt.getControllerId());
            final List<Action> activeActionsByTarget = deploymentManagement.findActiveActionsByTarget(t);
            assertThat(activeActionsByTarget).as("action should not be empty").isNotEmpty();
            assertThat(t.getTargetInfo().getUpdateStatus()).as("wrong target update status")
                    .isEqualTo(TargetUpdateStatus.PENDING);
            for (final Action ua : activeActionsByTarget) {
                assertThat(ua.getDistributionSet()).as("action has wrong ds").isEqualTo(ds);
            }
        }

        final List<TargetAssignDistributionSetEvent> events = eventHandlerStub.getEvents(10, TimeUnit.SECONDS);

        assertTargetAssignDistributionSetEvents(savedDeployedTargets, ds, events);
    }

    @Test
    @Description("Test that it is not possible to assign a distribution set that is not complete.")
    public void failDistributionSetAssigmentThatIsNotComplete() throws InterruptedException {
        eventHandlerStub.setExpectedNumberOfEvents(0);

        final List<Target> targets = testdataFactory.createTargets(10);

        final SoftwareModule ah = testdataFactory.createSoftwareModuleApp();
        final SoftwareModule os = testdataFactory.createSoftwareModuleOs();

        final DistributionSet incomplete = distributionSetManagement
                .createDistributionSet(entityFactory.distributionSet().create().name("incomplete").version("v1")
                        .type(standardDsType).modules(Lists.newArrayList(ah.getId())));

        try {
            assignDistributionSet(incomplete, targets);
            fail("expected IncompleteDistributionSetException");
        } catch (final IncompleteDistributionSetException ex) {
        }

        // give some chance to receive events asynchronously
        Thread.sleep(1L);
        final List<TargetAssignDistributionSetEvent> events = eventHandlerStub.getEvents(5, TimeUnit.SECONDS);
        assertThat(events).as("events should be empty").isEmpty();

        final DistributionSet nowComplete = distributionSetManagement.assignSoftwareModules(incomplete.getId(),
                Sets.newHashSet(os.getId()));

        eventHandlerStub.setExpectedNumberOfEvents(10);

        assertThat(assignDistributionSet(nowComplete, targets).getAssigned()).as("assign ds doesn't work")
                .isEqualTo(10);

        assertTargetAssignDistributionSetEvents(targets, nowComplete, eventHandlerStub.getEvents(15, TimeUnit.SECONDS));
    }

    @Test
    @Description("Multiple deployments or distribution set to target assignment test. Expected behaviour is that a new deployment "
            + "overides unfinished old one which are canceled as part of the operation.")
    public void mutipleDeployments() throws InterruptedException {
        final String undeployedTargetPrefix = "undep-T";
        final int noOfUndeployedTargets = 5;

        final String deployedTargetPrefix = "dep-T";
        final int noOfDeployedTargets = 4;

        final int noOfDistributionSets = 3;

        // Each of the four targets get one assignment (4 * 1 = 4)
        final int expectedNumberOfEventsForAssignment = 4;
        eventHandlerStub.setExpectedNumberOfEvents(expectedNumberOfEventsForAssignment);

        // Each of the four targets get two more assignment the which are
        // cancelled (4 * 2 = 8)
        final int expectedNumberOfEventsForCancel = 8;
        cancelEventHandlerStub.setExpectedNumberOfEvents(expectedNumberOfEventsForCancel);

        final DeploymentResult deploymentResult = prepareComplexRepo(undeployedTargetPrefix, noOfUndeployedTargets,
                deployedTargetPrefix, noOfDeployedTargets, noOfDistributionSets, "myTestDS");

        final List<Long> deployedTargetIDs = deploymentResult.getDeployedTargetIDs();
        final List<Long> undeployedTargetIDs = deploymentResult.getUndeployedTargetIDs();
        final Collection<JpaTarget> savedNakedTargets = (Collection) deploymentResult.getUndeployedTargets();
        final Collection<JpaTarget> savedDeployedTargets = (Collection) deploymentResult.getDeployedTargets();

        // retrieving all Actions created by the assignDistributionSet call
        final Page<JpaAction> page = actionRepository.findAll(pageReq);
        // and verify the number
        assertThat(page.getTotalElements()).as("wrong size of actions")
                .isEqualTo(noOfDeployedTargets * noOfDistributionSets);

        // only records retrieved from the DB can be evaluated to be sure that
        // all fields are
        // populated;
        final Iterable<JpaTarget> allFoundTargets = targetRepository.findAll();

        final Iterable<JpaTarget> deployedTargetsFromDB = targetRepository.findAll(deployedTargetIDs);
        final Iterable<JpaTarget> undeployedTargetsFromDB = targetRepository.findAll(undeployedTargetIDs);

        // test that number of Targets
        assertThat(allFoundTargets.spliterator().getExactSizeIfKnown()).as("number of target is wrong")
                .isEqualTo(deployedTargetsFromDB.spliterator().getExactSizeIfKnown()
                        + undeployedTargetsFromDB.spliterator().getExactSizeIfKnown());
        assertThat(deployedTargetsFromDB.spliterator().getExactSizeIfKnown()).as("number of target is wrong")
                .isEqualTo(noOfDeployedTargets);
        assertThat(undeployedTargetsFromDB.spliterator().getExactSizeIfKnown()).as("number of target is wrong")
                .isEqualTo(noOfUndeployedTargets);

        // test the content of different lists
        assertThat(allFoundTargets).as("content of founded target is wrong").containsAll(deployedTargetsFromDB)
                .containsAll(undeployedTargetsFromDB);
        assertThat(deployedTargetsFromDB).as("content of deployed target is wrong").containsAll(savedDeployedTargets)
                .doesNotContain(Iterables.toArray(undeployedTargetsFromDB, JpaTarget.class));
        assertThat(undeployedTargetsFromDB).as("content of undeployed target is wrong").containsAll(savedNakedTargets)
                .doesNotContain(Iterables.toArray(deployedTargetsFromDB, JpaTarget.class));

        // For each of the 4 targets 1 distribution sets gets assigned
        eventHandlerStub.getEvents(10, TimeUnit.SECONDS);

        // For each of the 4 targets 2 distribution sets gets cancelled
        cancelEventHandlerStub.getEvents(10, TimeUnit.SECONDS);

    }

    @Test
    @Description("Multiple deployments or distribution set to target assignment test including finished response "
            + "from target/controller. Expected behaviour is that in case of OK finished update the target will go to "
            + "IN_SYNC status and installed DS is set to the assigned DS entry.")
    public void assignDistributionSetAndAddFinishedActionStatus() {
        final PageRequest pageRequest = new PageRequest(0, 100, Direction.ASC, ActionStatusFields.ID.getFieldName());

        final DeploymentResult deployResWithDsA = prepareComplexRepo("undep-A-T", 2, "dep-A-T", 4, 1, "dsA");
        final DeploymentResult deployResWithDsB = prepareComplexRepo("undep-B-T", 3, "dep-B-T", 5, 1, "dsB");
        final DeploymentResult deployResWithDsC = prepareComplexRepo("undep-C-T", 4, "dep-C-T", 6, 1, "dsC");

        // keep a reference to the created DistributionSets
        final JpaDistributionSet dsA = (JpaDistributionSet) deployResWithDsA.getDistributionSets().get(0);
        final JpaDistributionSet dsB = (JpaDistributionSet) deployResWithDsB.getDistributionSets().get(0);
        final JpaDistributionSet dsC = (JpaDistributionSet) deployResWithDsC.getDistributionSets().get(0);

        // retrieving the UpdateActions created by the assignments
        actionRepository.findByDistributionSet(pageRequest, dsA).getContent().get(0);
        actionRepository.findByDistributionSet(pageRequest, dsB).getContent().get(0);
        actionRepository.findByDistributionSet(pageRequest, dsC).getContent().get(0);

        // verifying the correctness of the assignments
        for (final Target t : deployResWithDsA.getDeployedTargets()) {
            assertThat(t.getAssignedDistributionSet().getId()).as("assignment is not correct").isEqualTo(dsA.getId());
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo()
                    .getInstalledDistributionSet()).as("installed ds should be null").isNull();
        }
        for (final Target t : deployResWithDsB.getDeployedTargets()) {
            assertThat(t.getAssignedDistributionSet().getId()).as("assigned ds is wrong").isEqualTo(dsB.getId());
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo()
                    .getInstalledDistributionSet()).as("installed ds should be null").isNull();
        }
        for (final Target t : deployResWithDsC.getDeployedTargets()) {
            assertThat(t.getAssignedDistributionSet().getId()).isEqualTo(dsC.getId());
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo()
                    .getInstalledDistributionSet()).as("installed ds should not be null").isNull();
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo().getUpdateStatus())
                    .as("wrong target info update status").isEqualTo(TargetUpdateStatus.PENDING);
        }

        final List<Target> updatedTsDsA = testdataFactory
                .sendUpdateActionStatusToTargets(deployResWithDsA.getDeployedTargets(), Status.FINISHED,
                        Collections.singletonList("alles gut"))
                .stream().map(action -> action.getTarget()).collect(Collectors.toList());

        // verify, that dsA is deployed correctly
        assertThat(updatedTsDsA).as("ds is not deployed correctly").isEqualTo(deployResWithDsA.getDeployedTargets());
        for (final Target t_ : updatedTsDsA) {
            final Target t = targetManagement.findTargetByControllerID(t_.getControllerId());
            assertThat(t.getAssignedDistributionSet()).as("assigned ds is wrong").isEqualTo(dsA);
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo()
                    .getInstalledDistributionSet()).as("installed ds is wrong").isEqualTo(dsA);
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo().getUpdateStatus())
                    .as("wrong target info update status").isEqualTo(TargetUpdateStatus.IN_SYNC);
            assertThat(deploymentManagement.findActiveActionsByTarget(t)).as("no actions should be active").hasSize(0);
        }

        // deploy dsA to the target which already have dsB deployed -> must
        // remove updActB from
        // activeActions, add a corresponding cancelAction and another
        // UpdateAction for dsA
        final Iterable<Target> deployed2DS = assignDistributionSet(dsA, deployResWithDsB.getDeployedTargets())
                .getAssignedEntity();
        actionRepository.findByDistributionSet(pageRequest, dsA).getContent().get(1);

        // get final updated version of targets
        final List<Target> deployResWithDsBTargets = targetManagement.findTargetByControllerID(deployResWithDsB
                .getDeployedTargets().stream().map(target -> target.getControllerId()).collect(Collectors.toList()));

        assertThat(deployed2DS).as("deployed ds is wrong").containsAll(deployResWithDsBTargets);
        assertThat(deployed2DS).as("deployed ds is wrong").hasSameSizeAs(deployResWithDsBTargets);

        for (final Target t_ : deployed2DS) {
            final Target t = targetManagement.findTargetByControllerID(t_.getControllerId());
            assertThat(t.getAssignedDistributionSet()).as("assigned ds is wrong").isEqualTo(dsA);
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo()
                    .getInstalledDistributionSet()).as("installed ds should be null").isNull();
            assertThat(targetManagement.findTargetByControllerID(t.getControllerId()).getTargetInfo().getUpdateStatus())
                    .as("wrong target info update status").isEqualTo(TargetUpdateStatus.PENDING);

        }
    }

    /**
     * test the deletion of {@link DistributionSet}s including exception in case
     * of {@link Target}s are assigned by
     * {@link Target#getAssignedDistributionSet()} or
     * {@link Target#getInstalledDistributionSet()}
     */
    @Test
    @Description("Deletes distribution set. Expected behaviour is that a soft delete is performed "
            + "if the DS is assigned to a target and a hard delete if the DS is not in use at all.")
    public void deleteDistributionSet() {

        final PageRequest pageRequest = new PageRequest(0, 100, Direction.ASC, "id");

        final String undeployedTargetPrefix = "undep-T";
        final int noOfUndeployedTargets = 2;

        final String deployedTargetPrefix = "dep-T";
        final int noOfDeployedTargets = 4;

        final int noOfDistributionSets = 3;

        final DeploymentResult deploymentResult = prepareComplexRepo(undeployedTargetPrefix, noOfUndeployedTargets,
                deployedTargetPrefix, noOfDeployedTargets, noOfDistributionSets, "myTestDS");

        DistributionSet dsA = testdataFactory.createDistributionSet("");

        distributionSetManagement.deleteDistributionSet(dsA.getId());
        dsA = distributionSetManagement.findDistributionSetById(dsA.getId());
        assertThat(dsA).as("ds should be null").isNull();

        // // verify that the ds is not physically deleted
        for (final DistributionSet ds : deploymentResult.getDistributionSets()) {
            distributionSetManagement.deleteDistributionSet(ds.getId());
            final DistributionSet foundDS = distributionSetManagement.findDistributionSetById(ds.getId());
            assertThat(foundDS).as("founded should not be null").isNotNull();
            assertThat(foundDS.isDeleted()).as("found ds should be deleted").isTrue();
        }

        // verify that deleted attribute is used correctly
        List<DistributionSet> allFoundDS = distributionSetManagement
                .findDistributionSetsByDeletedAndOrCompleted(pageReq, false, true).getContent();
        assertThat(allFoundDS.size()).as("no ds should be founded").isEqualTo(0);
        allFoundDS = distributionSetManagement.findDistributionSetsByDeletedAndOrCompleted(pageRequest, true, true)
                .getContent();
        assertThat(allFoundDS).as("wrong size of founded ds").hasSize(noOfDistributionSets);

        for (final DistributionSet ds : deploymentResult.getDistributionSets()) {
            testdataFactory.sendUpdateActionStatusToTargets(deploymentResult.getDeployedTargets(), Status.FINISHED,
                    Collections.singletonList("blabla alles gut"));
        }
        // try to delete again
        distributionSetManagement.deleteDistributionSet(deploymentResult.getDistributionSetIDs()
                .toArray(new Long[deploymentResult.getDistributionSetIDs().size()]));
        // verify that the result is the same, even though distributionSet dsA
        // has been installed
        // successfully and no activeAction is referring to created distribution
        // sets
        allFoundDS = distributionSetManagement.findDistributionSetsByDeletedAndOrCompleted(pageRequest, false, true)
                .getContent();
        assertThat(allFoundDS.size()).as("no ds should be founded").isEqualTo(0);
        allFoundDS = distributionSetManagement.findDistributionSetsByDeletedAndOrCompleted(pageRequest, true, true)
                .getContent();
        assertThat(allFoundDS).as("size of founded ds is wrong").hasSize(noOfDistributionSets);

    }

    @Test
    @Description("Deletes multiple targets and verfies that all related metadat is also deleted.")
    public void deletesTargetsAndVerifyCascadeDeletes() {

        final String undeployedTargetPrefix = "undep-T";
        final int noOfUndeployedTargets = 2;

        final String deployedTargetPrefix = "dep-T";
        final int noOfDeployedTargets = 4;

        final int noOfDistributionSets = 3;

        final DeploymentResult deploymentResult = prepareComplexRepo(undeployedTargetPrefix, noOfUndeployedTargets,
                deployedTargetPrefix, noOfDeployedTargets, noOfDistributionSets, "myTestDS");

        for (final DistributionSet ds : deploymentResult.getDistributionSets()) {
            testdataFactory.sendUpdateActionStatusToTargets(deploymentResult.getDeployedTargets(), Status.FINISHED,
                    Collections.singletonList("blabla alles gut"));
        }

        assertThat(targetManagement.countTargetsAll()).as("size of targets is wrong").isNotZero();
        assertThat(actionStatusRepository.count()).as("size of action status is wrong").isNotZero();

        targetManagement
                .deleteTargets(deploymentResult.getUndeployedTargetIDs().toArray(new Long[noOfUndeployedTargets]));
        targetManagement.deleteTargets(deploymentResult.getDeployedTargetIDs().toArray(new Long[noOfDeployedTargets]));

        assertThat(targetManagement.countTargetsAll()).as("size of targets should be zero").isZero();
        assertThat(actionStatusRepository.count()).as("size of action status is wrong").isZero();
    }

    @Test
    @Description("Testing if changing target and the status without refreshing the entities from the DB (e.g. concurrent changes from UI and from controller) works")
    public void alternatingAssignmentAndAddUpdateActionStatus() {

        final DistributionSet dsA = testdataFactory.createDistributionSet("a");
        final DistributionSet dsB = testdataFactory.createDistributionSet("b");
        List<Target> targs = Lists.newArrayList(testdataFactory.createTarget("target-id-A"));

        // doing the assignment
        targs = assignDistributionSet(dsA, targs).getAssignedEntity();
        Target targ = targetManagement.findTargetByControllerID(targs.iterator().next().getControllerId());

        // checking the revisions of the created entities
        // verifying that the revision of the object and the revision within the
        // DB has not changed
        assertThat(dsA.getOptLockRevision()).as("lock revision is wrong").isEqualTo(
                distributionSetManagement.findDistributionSetByIdWithDetails(dsA.getId()).getOptLockRevision());

        // verifying that the assignment is correct
        assertThat(deploymentManagement.findActiveActionsByTarget(targ).size()).as("Active target actions are wrong")
                .isEqualTo(1);
        assertThat(deploymentManagement.findActionsByTarget(targ).size()).as("Target actions are wrong").isEqualTo(1);
        assertThat(targ.getTargetInfo().getUpdateStatus()).as("UpdateStatus of target is wrong")
                .isEqualTo(TargetUpdateStatus.PENDING);
        assertThat(targ.getAssignedDistributionSet()).as("Assigned distribution set of target is wrong").isEqualTo(dsA);
        assertThat(deploymentManagement.findActiveActionsByTarget(targ).get(0).getDistributionSet())
                .as("Distribution set of actionn is wrong").isEqualTo(dsA);
        assertThat(deploymentManagement.findActiveActionsByTarget(targ).get(0).getDistributionSet())
                .as("Installed distribution set of action should be null").isNotNull();

        final Page<Action> updAct = actionRepository.findByDistributionSet(pageReq, (JpaDistributionSet) dsA);
        controllerManagament.addUpdateActionStatus(
                entityFactory.actionStatus().create(updAct.getContent().get(0).getId()).status(Status.FINISHED));

        targ = targetManagement.findTargetByControllerID(targ.getControllerId());

        assertEquals("active target actions are wrong", 0, deploymentManagement.findActiveActionsByTarget(targ).size());
        assertEquals("active actions are wrong", 1, deploymentManagement.findInActiveActionsByTarget(targ).size());

        assertEquals("tagret update status is not correct", TargetUpdateStatus.IN_SYNC,
                targ.getTargetInfo().getUpdateStatus());
        assertEquals("wrong assigned ds", dsA, targ.getAssignedDistributionSet());
        assertEquals("wrong installed ds", dsA, targ.getTargetInfo().getInstalledDistributionSet());

        targs = assignDistributionSet(dsB.getId(), "target-id-A").getAssignedEntity();

        targ = targs.iterator().next();

        assertEquals("active actions are wrong", 1, deploymentManagement.findActiveActionsByTarget(targ).size());
        assertEquals("target status is wrong", TargetUpdateStatus.PENDING,
                targetManagement.findTargetByControllerID(targ.getControllerId()).getTargetInfo().getUpdateStatus());
        assertEquals("wrong assigned ds", dsB, targ.getAssignedDistributionSet());
        assertEquals("Installed ds is wrong", dsA.getId(),
                targetManagement.findTargetByControllerIDWithDetails(targ.getControllerId()).getTargetInfo()
                        .getInstalledDistributionSet().getId());
        assertEquals("Active ds is wrong", dsB,
                deploymentManagement.findActiveActionsByTarget(targ).get(0).getDistributionSet());

    }

    @Test
    @Description("The test verfies that the DS itself is not changed because of an target assignment"
            + " which is a relationship but not a changed on the entity itself..")
    public void checkThatDsRevisionsIsNotChangedWithTargetAssignment() {
        final DistributionSet dsA = testdataFactory.createDistributionSet("a");
        testdataFactory.createDistributionSet("b");
        final Target targ = testdataFactory.createTarget("target-id-A");

        assertThat(dsA.getOptLockRevision()).as("lock revision is wrong").isEqualTo(
                distributionSetManagement.findDistributionSetByIdWithDetails(dsA.getId()).getOptLockRevision());

        assignDistributionSet(dsA, Lists.newArrayList(targ));

        assertThat(dsA.getOptLockRevision()).as("lock revision is wrong").isEqualTo(
                distributionSetManagement.findDistributionSetByIdWithDetails(dsA.getId()).getOptLockRevision());
    }

    @Test
    @Description("Tests the switch from a soft to hard update by API")
    public void forceSoftAction() {
        // prepare
        final Target target = testdataFactory.createTarget("knownControllerId");
        final DistributionSet ds = testdataFactory.createDistributionSet("a");
        // assign ds to create an action
        final DistributionSetAssignmentResult assignDistributionSet = deploymentManagement.assignDistributionSet(
                ds.getId(), ActionType.SOFT,
                org.eclipse.hawkbit.repository.model.RepositoryModelConstants.NO_FORCE_TIME,
                Lists.newArrayList(target.getControllerId()));
        final Action action = deploymentManagement.findActionWithDetails(assignDistributionSet.getActions().get(0));
        // verify preparation
        Action findAction = deploymentManagement.findAction(action.getId());
        assertThat(findAction.getActionType()).as("action type is wrong").isEqualTo(ActionType.SOFT);

        // test
        deploymentManagement.forceTargetAction(action.getId());

        // verify test
        findAction = deploymentManagement.findAction(action.getId());
        assertThat(findAction.getActionType()).as("action type is wrong").isEqualTo(ActionType.FORCED);
    }

    @Test
    @Description("Tests the switch from a hard to hard update by API, e.g. which in fact should not change anything.")
    public void forceAlreadyForcedActionNothingChanges() {
        // prepare
        final Target target = testdataFactory.createTarget("knownControllerId");
        final DistributionSet ds = testdataFactory.createDistributionSet("a");
        // assign ds to create an action
        final DistributionSetAssignmentResult assignDistributionSet = deploymentManagement.assignDistributionSet(
                ds.getId(), ActionType.FORCED,
                org.eclipse.hawkbit.repository.model.RepositoryModelConstants.NO_FORCE_TIME,
                Lists.newArrayList(target.getControllerId()));
        final Action action = deploymentManagement.findActionWithDetails(assignDistributionSet.getActions().get(0));
        // verify perparation
        Action findAction = deploymentManagement.findAction(action.getId());
        assertThat(findAction.getActionType()).as("action type is wrong").isEqualTo(ActionType.FORCED);

        // test
        final Action forceTargetAction = deploymentManagement.forceTargetAction(action.getId());

        // verify test
        assertThat(forceTargetAction.getActionType()).as("action type is wrong").isEqualTo(ActionType.FORCED);
        findAction = deploymentManagement.findAction(action.getId());
        assertThat(findAction.getActionType()).as("action type is wrong").isEqualTo(ActionType.FORCED);
    }

    /**
     * Helper methods that creates 2 lists of targets and a list of distribution
     * sets.
     * <p>
     * <b>All created distribution sets are assigned to all targets of the
     * target list deployedTargets.</b>
     *
     * @param undeployedTargetPrefix
     *            prefix to be used as target controller prefix
     * @param noOfUndeployedTargets
     *            number of targets which remain undeployed
     * @param deployedTargetPrefix
     *            prefix to be used as target controller prefix
     * @param noOfDeployedTargets
     *            number of targets to which the created distribution sets
     *            assigned
     * @param noOfDistributionSets
     *            number of distribution sets
     * @param distributionSetPrefix
     *            prefix for the created distribution sets
     * @return the {@link DeploymentResult} containing all created targets, the
     *         distribution sets, the corresponding IDs for later evaluation in
     *         tests
     */
    private DeploymentResult prepareComplexRepo(final String undeployedTargetPrefix, final int noOfUndeployedTargets,
            final String deployedTargetPrefix, final int noOfDeployedTargets, final int noOfDistributionSets,
            final String distributionSetPrefix) {
        final Iterable<Target> nakedTargets = testdataFactory.createTargets(noOfUndeployedTargets,
                undeployedTargetPrefix, "first description");

        List<Target> deployedTargets = testdataFactory.createTargets(noOfDeployedTargets, deployedTargetPrefix,
                "first description");

        // creating 10 DistributionSets
        final Collection<DistributionSet> dsList = testdataFactory.createDistributionSets(distributionSetPrefix,
                noOfDistributionSets);
        String time = String.valueOf(System.currentTimeMillis());
        time = time.substring(time.length() - 5);

        // assigning all DistributionSet to the Target in the list
        // deployedTargets
        for (final DistributionSet ds : dsList) {
            deployedTargets = assignDistributionSet(ds, deployedTargets).getAssignedEntity();
        }

        final DeploymentResult deploymentResult = new DeploymentResult(deployedTargets, nakedTargets, dsList,
                deployedTargetPrefix, undeployedTargetPrefix, distributionSetPrefix);
        return deploymentResult;

    }

    private void assertTargetAssignDistributionSetEvents(final List<Target> targets, final DistributionSet ds,
            final List<TargetAssignDistributionSetEvent> events) {
        assertThat(events).isNotEmpty();
        for (final Target myt : targets) {
            boolean found = false;
            for (final TargetAssignDistributionSetEvent event : events) {
                if (event.getControllerId().equals(myt.getControllerId())) {
                    found = true;
                    final List<Action> activeActionsByTarget = deploymentManagement.findActiveActionsByTarget(myt);
                    assertThat(activeActionsByTarget).as("size of active actions for target is wrong").isNotEmpty();
                    assertThat(event.getActionId()).as("Action id in database and event do not match")
                            .isEqualTo(activeActionsByTarget.get(0).getId());

                    assertThat(distributionSetManagement.findDistributionSetById(event.getDistributionSetId())
                            .getModules()).as("softwaremodule size is not correct")
                                    .containsOnly(ds.getModules().toArray(new SoftwareModule[ds.getModules().size()]));
                }
            }
            assertThat(found).as("No event found for controller " + myt.getControllerId()).isTrue();
        }
    }

    private class DeploymentResult {
        final List<Long> deployedTargetIDs = new ArrayList<>();
        final List<Long> undeployedTargetIDs = new ArrayList<>();
        final List<Long> distributionSetIDs = new ArrayList<>();

        private final List<Target> undeployedTargets = new ArrayList<>();
        private final List<Target> deployedTargets = new ArrayList<>();
        private final List<DistributionSet> distributionSets = new ArrayList<>();

        public DeploymentResult(final Iterable<Target> deployedTs, final Iterable<Target> undeployedTs,
                final Iterable<DistributionSet> dss, final String deployedTargetPrefix,
                final String undeployedTargetPrefix, final String distributionSetPrefix) {

            Iterables.addAll(deployedTargets, deployedTs);
            Iterables.addAll(undeployedTargets, undeployedTs);
            Iterables.addAll(distributionSets, dss);

            deployedTargets.forEach(t -> deployedTargetIDs.add(t.getId()));

            undeployedTargets.forEach(t -> undeployedTargetIDs.add(t.getId()));

            distributionSets.forEach(ds -> distributionSetIDs.add(ds.getId()));

        }

        public List<Long> getDistributionSetIDs() {
            return distributionSetIDs;
        }

        public List<Long> getDeployedTargetIDs() {
            return deployedTargetIDs;
        }

        public List<Target> getUndeployedTargets() {
            return undeployedTargets;
        }

        public List<DistributionSet> getDistributionSets() {
            return distributionSets;
        }

        public List<Target> getDeployedTargets() {
            return deployedTargets;
        }

        public List<Long> getUndeployedTargetIDs() {
            return undeployedTargetIDs;
        }

    }

    protected static class EventHandlerStub implements ApplicationListener<TargetAssignDistributionSetEvent> {
        private final List<TargetAssignDistributionSetEvent> events = Collections.synchronizedList(new LinkedList<>());
        private CountDownLatch latch;
        private int expectedNumberOfEvents;

        /**
         * @param expectedNumberOfEvents
         *            the expectedNumberOfEvents to set
         */
        public void setExpectedNumberOfEvents(final int expectedNumberOfEvents) {
            events.clear();
            this.expectedNumberOfEvents = expectedNumberOfEvents;
            this.latch = new CountDownLatch(expectedNumberOfEvents);
        }

        public List<TargetAssignDistributionSetEvent> getEvents(final long timeout, final TimeUnit unit)
                throws InterruptedException {
            latch.await(timeout, unit);
            final List<TargetAssignDistributionSetEvent> handledEvents = Collections
                    .unmodifiableList(new LinkedList<>(events));
            assertThat(handledEvents).as("Did not receive the expected amount of events (" + expectedNumberOfEvents
                    + ") within timeout. Received events are " + handledEvents).hasSize(expectedNumberOfEvents);
            return handledEvents;

        }

        @Override
        public void onApplicationEvent(final TargetAssignDistributionSetEvent event) {
            if (latch == null) {
                return;
            }
            events.add(event);
            latch.countDown();

        }
    }

    private static class CancelEventHandlerStub implements ApplicationListener<CancelTargetAssignmentEvent> {
        private final List<CancelTargetAssignmentEvent> events = Collections.synchronizedList(new LinkedList<>());
        private CountDownLatch latch;
        private int expectedNumberOfEvents;

        public void setExpectedNumberOfEvents(final int expectedNumberOfEvents) {
            events.clear();
            this.expectedNumberOfEvents = expectedNumberOfEvents;
            this.latch = new CountDownLatch(expectedNumberOfEvents);
        }

        public List<CancelTargetAssignmentEvent> getEvents(final long timeout, final TimeUnit unit)
                throws InterruptedException {
            latch.await(timeout, unit);
            final List<CancelTargetAssignmentEvent> handledEvents = new LinkedList<>(events);
            assertThat(handledEvents).as("Did not receive the expected amount of events (" + expectedNumberOfEvents
                    + ") within timeout. Received events are " + handledEvents).hasSize(expectedNumberOfEvents);
            return handledEvents;
        }

        @Override
        public void onApplicationEvent(final CancelTargetAssignmentEvent event) {
            if (latch == null) {
                return;
            }
            events.add(event);
            latch.countDown();
        }
    }

}